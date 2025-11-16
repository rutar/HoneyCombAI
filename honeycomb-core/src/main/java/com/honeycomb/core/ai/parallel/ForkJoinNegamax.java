package com.honeycomb.core.ai.parallel;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import com.honeycomb.core.ai.SearchConstraints;
import com.honeycomb.core.ai.SearchConstraints.SearchMode;
import com.honeycomb.core.ai.SearchResult;
import com.honeycomb.core.ai.Searcher;
import com.honeycomb.core.ai.SearchTelemetry;
import com.honeycomb.core.ai.state.SearchState;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

        long rootBits = state.getBoard().getBits();
        boolean rootFirst = state.getBoard().isFirstPlayer();
        int rootFirstScore = state.getScore(true);
        int rootSecondScore = state.getScore(false);

        RootTask rootTask = new RootTask(rootBits, rootFirst, rootFirstScore, rootSecondScore, boundedDepth, context);
        RootResult result = pool.invoke(rootTask);
        long elapsedNanos = System.nanoTime() - searchStart;

        long visitedNodes = context.visitedNodes();
        boolean timedOut = context.wasAborted();
        int depthEvaluated = boundedDepth;

        int bestMove = result.bestMove;
        if (bestMove < 0) {
            long available = (~rootBits) & FULL_BOARD_MASK;
            if (available == 0) {
                throw new IllegalStateException("No legal moves available");
            }
            bestMove = Long.numberOfTrailingZeros(available);
        }

        stopRequested.set(false);

        SearchTelemetry.Iteration iteration = new SearchTelemetry.Iteration(depthEvaluated, visitedNodes,
                context.cutoffs(), 0L, 0L, context.pvReSearches(), context.maxActiveTasks(), elapsedNanos, List.of());
        SearchTelemetry telemetry = new SearchTelemetry(List.of(iteration));
        return new SearchResult(bestMove, depthEvaluated, visitedNodes, timedOut, telemetry);
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

    private RootResult searchRoot(long boardBits, boolean firstToMove, int firstScore, int secondScore, int depth,
            SearchContext context) {
        SearchState state = context.acquireState();
        try {
            state.initialize(boardBits, firstToMove, firstScore, secondScore);

            int rootDepth = state.ply();
            int moveCount = state.generateMoves();
            if (moveCount == 0) {
                return new RootResult(-1, Integer.MIN_VALUE);
            }

            if (context.shouldAbort()) {
                return new RootResult(-1, Integer.MIN_VALUE);
            }

            int alpha = Integer.MIN_VALUE / 2;
            int beta = Integer.MAX_VALUE / 2;
            int bestMove = -1;
            int bestScore = Integer.MIN_VALUE;

            state.pushGenerated(rootDepth, 0);
            int score = -searchImpl(state, depth - 1, -beta, -alpha, true, context);
            state.pop();
            bestScore = score;
            bestMove = state.moveAt(rootDepth, 0);
            alpha = Math.max(alpha, score);

            if (context.shouldAbort() || moveCount == 1 || alpha >= beta) {
                if (alpha >= beta) {
                    context.recordCutoff();
                }
                return new RootResult(bestMove, bestScore);
            }

            if (!context.enableParallelSplits) {
                for (int i = 1; i < moveCount; i++) {
                    if (context.shouldAbort()) {
                        break;
                    }

                    int move = state.moveAt(rootDepth, i);
                    state.pushGenerated(rootDepth, i);
                    int probe = -searchImpl(state, depth - 1, -alpha - 1, -alpha, false, context);
                    state.pop();
                    int candidate = probe;
                    if (candidate > alpha) {
                        state.pushGenerated(rootDepth, i);
                        context.recordPvResearch();
                        candidate = -searchImpl(state, depth - 1, -beta, -alpha, true, context);
                        state.pop();
                    }
                    if (candidate > bestScore) {
                        bestScore = candidate;
                        bestMove = move;
                    }
                    if (candidate > alpha) {
                        alpha = candidate;
                    }
                    if (alpha >= beta) {
                        context.recordCutoff();
                        break;
                    }
                }
                return new RootResult(bestMove, bestScore);
            }

            List<ChildTask> siblingTasks = new ArrayList<>(moveCount - 1);
            for (int i = 1; i < moveCount; i++) {
                if (context.shouldAbort()) {
                    break;
                }
                int move = state.moveAt(rootDepth, i);
                long childBits = boardBits | (1L << move);
                int delta = state.moveDeltaAt(rootDepth, i);
                int childFirstScore = firstScore + (firstToMove ? delta : 0);
                int childSecondScore = secondScore + (firstToMove ? 0 : delta);
                SearchComputeTask task = new SearchComputeTask(childBits, !firstToMove, childFirstScore,
                        childSecondScore, depth - 1, -alpha - 1, -alpha, false, context);
                if (context.shouldAbort()) {
                    break;
                }
                siblingTasks.add(new ChildTask(move, i, task));
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
                    state.pushGenerated(rootDepth, childTask.moveIndex);
                    context.recordPvResearch();
                    candidate = -searchImpl(state, depth - 1, -beta, -alpha, true, context);
                    state.pop();
                }

                if (candidate > bestScore) {
                    bestScore = candidate;
                    bestMove = childTask.move;
                }
                if (candidate > alpha) {
                    alpha = candidate;
                }
                if (alpha >= beta) {
                    context.recordCutoff();
                    cutoff = true;
                }
            }

            for (ChildTask childTask : siblingTasks) {
                childTask.cancel();
            }

            return new RootResult(bestMove, bestScore);
        } finally {
            context.releaseState(state);
        }
    }

    private int searchImpl(SearchState state, int depth, int alpha, int beta, boolean pvNode, SearchContext context) {
        if (context.shouldAbort()) {
            return evaluate(state);
        }

        context.incrementVisited();

        if (depth <= 0 || state.isTerminal()) {
            return evaluate(state);
        }

        int currentPly = state.ply();
        int moveCount = state.generateMoves();
        if (moveCount == 0) {
            return evaluate(state);
        }

        int bestValue = Integer.MIN_VALUE;

        if (pvNode && moveCount > 0) {
            if (context.shouldAbort()) {
                return bestValue == Integer.MIN_VALUE ? evaluate(state) : bestValue;
            }
            state.pushGenerated(currentPly, 0);
            int score = -searchImpl(state, depth - 1, -beta, -alpha, true, context);
            state.pop();
            bestValue = Math.max(bestValue, score);
            alpha = Math.max(alpha, score);

            if (!context.shouldAbort() && alpha < beta && moveCount > 1) {
                if (!context.enableParallelSplits) {
                    for (int i = 1; i < moveCount; i++) {
                        if (context.shouldAbort()) {
                            break;
                        }
                        state.pushGenerated(currentPly, i);
                        int probe = -searchImpl(state, depth - 1, -alpha - 1, -alpha, false, context);
                        state.pop();
                        int candidate = probe;
                        if (candidate > alpha) {
                            state.pushGenerated(currentPly, i);
                            context.recordPvResearch();
                            candidate = -searchImpl(state, depth - 1, -beta, -alpha, true, context);
                            state.pop();
                        }
                        if (candidate > bestValue) {
                            bestValue = candidate;
                        }
                        if (candidate > alpha) {
                            alpha = candidate;
                        }
                        if (alpha >= beta) {
                            context.recordCutoff();
                            break;
                        }
                    }
                } else {
                    List<ChildTask> siblingTasks = new ArrayList<>(moveCount - 1);
                    long boardBits = state.boardAt(currentPly);
                    boolean firstToMove = state.isFirstPlayerTurn(currentPly);
                    int firstScore = state.firstScore(currentPly);
                    int secondScore = state.secondScore(currentPly);
                    for (int i = 1; i < moveCount; i++) {
                        if (context.shouldAbort()) {
                            break;
                        }
                        int move = state.moveAt(currentPly, i);
                        long childBits = boardBits | (1L << move);
                        int delta = state.moveDeltaAt(currentPly, i);
                        int childFirstScore = firstScore + (firstToMove ? delta : 0);
                        int childSecondScore = secondScore + (firstToMove ? 0 : delta);
                        SearchComputeTask task = new SearchComputeTask(childBits, !firstToMove, childFirstScore,
                                childSecondScore, depth - 1, -alpha - 1, -alpha, false, context);
                        if (context.shouldAbort()) {
                            break;
                        }
                        siblingTasks.add(new ChildTask(move, i, task));
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
                            state.pushGenerated(currentPly, childTask.moveIndex);
                            context.recordPvResearch();
                            candidate = -searchImpl(state, depth - 1, -beta, -alpha, true, context);
                            state.pop();
                        }
                        if (candidate > bestValue) {
                            bestValue = candidate;
                        }
                        if (candidate > alpha) {
                            alpha = candidate;
                        }
                        if (alpha >= beta) {
                            context.recordCutoff();
                            cutoff = true;
                        }
                    }

                    for (ChildTask childTask : siblingTasks) {
                        childTask.cancel();
                    }
                }
            }
            return bestValue == Integer.MIN_VALUE ? evaluate(state) : bestValue;
        }

        for (int i = 0; i < moveCount; i++) {
            if (context.shouldAbort()) {
                break;
            }
            state.pushGenerated(currentPly, i);
            int score = -searchImpl(state, depth - 1, -beta, -alpha, pvNode, context);
            state.pop();
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
                context.recordCutoff();
                break;
            }
        }

        return bestValue == Integer.MIN_VALUE ? evaluate(state) : bestValue;
    }

    private int evaluate(SearchState state) {
        return state.evaluateCurrent(SCORE_WEIGHT);
    }

    private final class RootTask extends RecursiveTask<RootResult> {

        private final long boardBits;
        private final boolean firstToMove;
        private final int firstScore;
        private final int secondScore;
        private final int depth;
        private final SearchContext context;

        private RootTask(long boardBits, boolean firstToMove, int firstScore, int secondScore, int depth,
                SearchContext context) {
            this.boardBits = boardBits;
            this.firstToMove = firstToMove;
            this.firstScore = firstScore;
            this.secondScore = secondScore;
            this.depth = depth;
            this.context = context;
        }

        @Override
        protected RootResult compute() {
            context.taskStarted();
            try {
                return searchRoot(boardBits, firstToMove, firstScore, secondScore, depth, context);
            } finally {
                context.taskFinished();
            }
        }
    }

    private final class SearchComputeTask extends RecursiveTask<Integer> {

        private final long boardBits;
        private final boolean firstToMove;
        private final int firstScore;
        private final int secondScore;
        private final int depth;
        private final int alpha;
        private final int beta;
        private final boolean pvNode;
        private final SearchContext context;

        private SearchComputeTask(long boardBits, boolean firstToMove, int firstScore, int secondScore, int depth,
                int alpha, int beta, boolean pvNode, SearchContext context) {
            this.boardBits = boardBits;
            this.firstToMove = firstToMove;
            this.firstScore = firstScore;
            this.secondScore = secondScore;
            this.depth = depth;
            this.alpha = alpha;
            this.beta = beta;
            this.pvNode = pvNode;
            this.context = context;
        }

        @Override
        protected Integer compute() {
            context.taskStarted();
            SearchState state = context.acquireState();
            try {
                state.initialize(boardBits, firstToMove, firstScore, secondScore);
                return searchImpl(state, depth, alpha, beta, pvNode, context);
            } finally {
                context.releaseState(state);
                context.taskFinished();
            }
        }
    }

    private final class ChildTask {
        private final int move;
        private final int moveIndex;
        private final SearchComputeTask task;

        private ChildTask(int move, int moveIndex, SearchComputeTask task) {
            this.move = move;
            this.moveIndex = moveIndex;
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
        private final AtomicLong cutoffs = new AtomicLong();
        private final AtomicLong pvReSearches = new AtomicLong();
        private final AtomicInteger activeTasks = new AtomicInteger();
        private final AtomicLong maxActiveTasks = new AtomicLong();
        private final ThreadLocal<ArrayDeque<SearchState>> states = ThreadLocal.withInitial(ArrayDeque::new);
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

        private void recordCutoff() {
            cutoffs.incrementAndGet();
        }

        private long cutoffs() {
            return cutoffs.get();
        }

        private void recordPvResearch() {
            pvReSearches.incrementAndGet();
        }

        private long pvReSearches() {
            return pvReSearches.get();
        }

        private void taskStarted() {
            int current = activeTasks.incrementAndGet();
            maxActiveTasks.accumulateAndGet(current, Math::max);
        }

        private void taskFinished() {
            activeTasks.decrementAndGet();
        }

        private long maxActiveTasks() {
            return maxActiveTasks.get();
        }

        private boolean wasAborted() {
            return aborted;
        }

        private SearchState acquireState() {
            ArrayDeque<SearchState> pool = states.get();
            SearchState state = pool.pollFirst();
            if (state == null) {
                state = new SearchState();
            }
            return state;
        }

        private void releaseState(SearchState state) {
            if (state == null) {
                return;
            }
            states.get().offerFirst(state);
        }
    }
}
