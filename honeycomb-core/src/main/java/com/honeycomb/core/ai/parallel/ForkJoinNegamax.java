package com.honeycomb.core.ai.parallel;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import com.honeycomb.core.ScoreCalculator;
import com.honeycomb.core.ai.SearchConstraints;
import com.honeycomb.core.ai.SearchConstraints.SearchMode;
import com.honeycomb.core.ai.SearchResult;
import com.honeycomb.core.ai.Searcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel negamax searcher leveraging fork-join style root and PV splits.
 */
public final class ForkJoinNegamax implements Searcher {

    private static final int SCORE_WEIGHT = 100;
    private static final long FULL_BOARD_MASK = -1L >>> (64 - Board.CELL_COUNT);

    private final ForkJoinPool pool;
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    public ForkJoinNegamax() {
        this(Runtime.getRuntime().availableProcessors());
    }

    public ForkJoinNegamax(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be at least 1");
        }
        this.pool = new ForkJoinPool(parallelism);
    }

    public ForkJoinNegamax(ForkJoinPool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    /**
     * Requests cooperative cancellation of the currently running search.
     */
    public void requestStop() {
        stopRequested.set(true);
    }

    /**
     * Shuts down the internally managed {@link ForkJoinPool} if this instance created it.
     */
    public void shutdown() {
        pool.shutdown();
    }

    @Override
    public SearchResult search(GameState state, SearchConstraints constraints) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(constraints, "constraints");

        if (state.isGameOver()) {
            throw new IllegalStateException("Cannot search moves in a terminal position");
        }

        int requestedDepth = constraints.depthLimit();
        if (requestedDepth < 1) {
            throw new IllegalArgumentException("Depth limit must be at least 1");
        }

        int boundedDepth = Math.min(requestedDepth, Board.CELL_COUNT - state.getBoard().countBits());
        boundedDepth = Math.max(1, boundedDepth);

        long timeLimitNanos = toTimeLimitNanos(constraints.timeLimit());
        long searchStart = System.nanoTime();
        long deadline = timeLimitNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : saturatingAdd(searchStart, timeLimitNanos);

        stopRequested.set(false);
        SearchContext context = new SearchContext(deadline, stopRequested, constraints.mode());

        Node root = new Node(state.getBoard().getBits(), state.getBoard().isFirstPlayer(),
                state.getScore(true), state.getScore(false));

        RootTask rootTask = new RootTask(root, boundedDepth, context);
        RootResult result = pool.invoke(rootTask);

        long visitedNodes = context.visitedNodes();
        boolean timedOut = context.wasAborted();
        int depthEvaluated = boundedDepth;

        int bestMove = result.bestMove;
        if (bestMove < 0) {
            long available = root.availableMoves();
            if (available == 0) {
                throw new IllegalStateException("No legal moves available");
            }
            bestMove = Long.numberOfTrailingZeros(available);
        }

        stopRequested.set(false);

        return new SearchResult(bestMove, depthEvaluated, visitedNodes, timedOut);
    }

    private long toTimeLimitNanos(Duration timeLimit) {
        long nanos = timeLimit.isZero() ? Long.MAX_VALUE : timeLimit.toNanos();
        return nanos <= 0L ? 1L : nanos;
    }

    private long saturatingAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private final class RootTask extends RecursiveTask<RootResult> {

        private final Node rootNode;
        private final int depth;
        private final SearchContext context;

        private RootTask(Node rootNode, int depth, SearchContext context) {
            this.rootNode = rootNode;
            this.depth = depth;
            this.context = context;
        }

        @Override
        protected RootResult compute() {
            return searchRoot(rootNode, depth, context);
        }
    }

    private RootResult searchRoot(Node node, int depth, SearchContext context) {
        long available = node.availableMoves();
        if (available == 0) {
            return new RootResult(-1, Integer.MIN_VALUE);
        }

        int[] moves = extractMoves(available);
        int alpha = Integer.MIN_VALUE / 2;
        int beta = Integer.MAX_VALUE / 2;
        int bestMove = -1;
        int bestScore = Integer.MIN_VALUE;

        Node firstChild = node.child(moves[0]);
        int score = -searchImpl(firstChild, depth - 1, -beta, -alpha, true, context);
        bestScore = score;
        bestMove = moves[0];
        alpha = Math.max(alpha, score);

        if (context.shouldAbort() || moves.length == 1 || alpha >= beta) {
            return new RootResult(bestMove, bestScore);
        }

        if (!context.enableParallelSplits) {
            for (int i = 1; i < moves.length; i++) {
                if (context.shouldAbort()) {
                    break;
                }
                int move = moves[i];
                Node child = node.child(move);
                int probe = -searchImpl(child, depth - 1, -alpha - 1, -alpha, false, context);
                int candidate = probe;
                if (candidate > alpha) {
                    candidate = -searchImpl(child, depth - 1, -beta, -alpha, true, context);
                }
                if (candidate > bestScore) {
                    bestScore = candidate;
                    bestMove = move;
                }
                if (candidate > alpha) {
                    alpha = candidate;
                }
                if (alpha >= beta) {
                    break;
                }
            }
            return new RootResult(bestMove, bestScore);
        }

        List<ChildTask> siblingTasks = new ArrayList<>(moves.length - 1);
        for (int i = 1; i < moves.length; i++) {
            int move = moves[i];
            Node child = node.child(move);
            SearchComputeTask task = new SearchComputeTask(child, depth - 1, -alpha - 1, -alpha, false, context);
            siblingTasks.add(new ChildTask(move, child, task));
            task.fork();
        }

        boolean cutoff = false;
        for (ChildTask childTask : siblingTasks) {
            if (cutoff || context.shouldAbort()) {
                childTask.cancel();
                continue;
            }

            int probe;
            try {
                probe = -childTask.task.join();
            } catch (CancellationException ex) {
                continue;
            }

            int candidate = probe;
            if (candidate > alpha) {
                candidate = -searchImpl(childTask.node, depth - 1, -beta, -alpha, true, context);
            }

            if (candidate > bestScore) {
                bestScore = candidate;
                bestMove = childTask.move;
            }
            if (candidate > alpha) {
                alpha = candidate;
            }
            if (alpha >= beta) {
                cutoff = true;
            }
        }

        for (ChildTask childTask : siblingTasks) {
            childTask.cancel();
        }

        return new RootResult(bestMove, bestScore);
    }

    private int searchImpl(Node node, int depth, int alpha, int beta, boolean pvNode, SearchContext context) {
        if (context.shouldAbort()) {
            return evaluate(node);
        }

        context.incrementVisited();

        if (depth <= 0 || node.isTerminal()) {
            return evaluate(node);
        }

        long available = node.availableMoves();
        if (available == 0) {
            return evaluate(node);
        }

        int[] moves = extractMoves(available);
        int bestValue = Integer.MIN_VALUE;

        if (pvNode && moves.length > 0) {
            Node firstChild = node.child(moves[0]);
            int score = -searchImpl(firstChild, depth - 1, -beta, -alpha, true, context);
            bestValue = Math.max(bestValue, score);
            alpha = Math.max(alpha, score);

            if (!context.shouldAbort() && alpha < beta && moves.length > 1) {
                if (!context.enableParallelSplits) {
                    for (int i = 1; i < moves.length; i++) {
                        if (context.shouldAbort()) {
                            break;
                        }
                        int move = moves[i];
                        Node child = node.child(move);
                        int probe = -searchImpl(child, depth - 1, -alpha - 1, -alpha, false, context);
                        int candidate = probe;
                        if (candidate > alpha) {
                            candidate = -searchImpl(child, depth - 1, -beta, -alpha, true, context);
                        }
                        if (candidate > bestValue) {
                            bestValue = candidate;
                        }
                        if (candidate > alpha) {
                            alpha = candidate;
                        }
                        if (alpha >= beta) {
                            break;
                        }
                    }
                } else {
                    List<ChildTask> siblingTasks = new ArrayList<>(moves.length - 1);
                    for (int i = 1; i < moves.length; i++) {
                        int move = moves[i];
                        Node child = node.child(move);
                        SearchComputeTask task = new SearchComputeTask(child, depth - 1, -alpha - 1, -alpha, false, context);
                        siblingTasks.add(new ChildTask(move, child, task));
                        task.fork();
                    }

                    boolean cutoff = false;
                    for (ChildTask childTask : siblingTasks) {
                        if (cutoff || context.shouldAbort()) {
                            childTask.cancel();
                            continue;
                        }

                        int probe;
                        try {
                            probe = -childTask.task.join();
                        } catch (CancellationException ex) {
                            continue;
                        }

                        int candidate = probe;
                        if (candidate > alpha) {
                            candidate = -searchImpl(childTask.node, depth - 1, -beta, -alpha, true, context);
                        }

                        if (candidate > bestValue) {
                            bestValue = candidate;
                        }
                        if (candidate > alpha) {
                            alpha = candidate;
                        }
                        if (alpha >= beta) {
                            cutoff = true;
                        }
                    }

                    for (ChildTask childTask : siblingTasks) {
                        childTask.cancel();
                    }
                }
            }
            return bestValue == Integer.MIN_VALUE ? evaluate(node) : bestValue;
        }

        for (int move : moves) {
            Node child = node.child(move);
            int score = -searchImpl(child, depth - 1, -beta, -alpha, pvNode, context);
            if (score > bestValue) {
                bestValue = score;
            }
            if (context.shouldAbort()) {
                break;
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                break;
            }
        }

        return bestValue == Integer.MIN_VALUE ? evaluate(node) : bestValue;
    }

    private int evaluate(Node node) {
        int diff = node.firstScore - node.secondScore;
        int perspective = node.firstToMove ? diff : -diff;
        return perspective * SCORE_WEIGHT + bestPotential(node.boardBits);
    }

    private int bestPotential(long boardBits) {
        long available = (~boardBits) & FULL_BOARD_MASK;
        int best = 0;
        while (available != 0) {
            int move = Long.numberOfTrailingZeros(available);
            available &= available - 1;
            long updated = boardBits | (1L << move);
            int delta = ScoreCalculator.calculateScoreDelta(boardBits, updated, move);
            if (delta > best) {
                best = delta;
            }
        }
        return best;
    }

    private int[] extractMoves(long available) {
        int count = Long.bitCount(available);
        int[] moves = new int[count];
        for (int i = 0; i < count; i++) {
            int move = Long.numberOfTrailingZeros(available);
            moves[i] = move;
            available &= available - 1;
        }
        return moves;
    }

    private static final class Node {
        private final long boardBits;
        private final boolean firstToMove;
        private final int firstScore;
        private final int secondScore;

        private Node(long boardBits, boolean firstToMove, int firstScore, int secondScore) {
            this.boardBits = boardBits;
            this.firstToMove = firstToMove;
            this.firstScore = firstScore;
            this.secondScore = secondScore;
        }

        private Node child(int move) {
            long updatedBits = boardBits | (1L << move);
            int delta = ScoreCalculator.calculateScoreDelta(boardBits, updatedBits, move);
            int nextFirst = firstToMove ? firstScore + delta : firstScore;
            int nextSecond = firstToMove ? secondScore : secondScore + delta;
            return new Node(updatedBits, !firstToMove, nextFirst, nextSecond);
        }

        private long availableMoves() {
            return (~boardBits) & FULL_BOARD_MASK;
        }

        private boolean isTerminal() {
            return boardBits == FULL_BOARD_MASK;
        }
    }

    private final class SearchComputeTask extends RecursiveTask<Integer> {

        private final Node node;
        private final int depth;
        private final int alpha;
        private final int beta;
        private final boolean pvNode;
        private final SearchContext context;

        private SearchComputeTask(Node node, int depth, int alpha, int beta, boolean pvNode, SearchContext context) {
            this.node = node;
            this.depth = depth;
            this.alpha = alpha;
            this.beta = beta;
            this.pvNode = pvNode;
            this.context = context;
        }

        @Override
        protected Integer compute() {
            return searchImpl(node, depth, alpha, beta, pvNode, context);
        }
    }

    private final class ChildTask {
        private final int move;
        private final Node node;
        private final SearchComputeTask task;

        private ChildTask(int move, Node node, SearchComputeTask task) {
            this.move = move;
            this.node = node;
            this.task = task;
        }

        private void cancel() {
            if (!task.isDone()) {
                task.cancel(true);
                try {
                    task.join();
                } catch (CancellationException ignored) {
                    // ignore
                }
            }
        }
    }

    private static final class RootResult {
        private final int bestMove;
        private final int bestScore;

        private RootResult(int bestMove, int bestScore) {
            this.bestMove = bestMove;
            this.bestScore = bestScore;
        }
    }

    private static final class SearchContext {
        private final long deadline;
        private final AtomicBoolean stopFlag;
        private final boolean enableParallelSplits;
        private final AtomicLong visitedNodes = new AtomicLong();
        private volatile boolean aborted;

        private SearchContext(long deadline, AtomicBoolean stopFlag, SearchMode mode) {
            this.deadline = deadline;
            this.stopFlag = stopFlag;
            this.enableParallelSplits = mode != SearchMode.SEQ;
        }

        private boolean shouldAbort() {
            if (aborted) {
                return true;
            }
            if (stopFlag.get()) {
                aborted = true;
                return true;
            }
            if (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline) {
                aborted = true;
                return true;
            }
            return false;
        }

        private void incrementVisited() {
            visitedNodes.incrementAndGet();
        }

        private long visitedNodes() {
            return visitedNodes.get();
        }

        private boolean wasAborted() {
            return aborted;
        }
    }
}
