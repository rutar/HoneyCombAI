package com.honeycomb.core.ai;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import com.honeycomb.core.ScoreCalculator;
import com.honeycomb.core.Symmetry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Negamax searcher with depth and time controls.
 */
public final class NegamaxAI {

    private static final Logger LOGGER = Logger.getLogger(NegamaxAI.class.getName());
    private static final int SCORE_WEIGHT = 100;
    private static final long FULL_BOARD_MASK = -1L >>> (64 - Board.CELL_COUNT);

    private final int maxDepth;
    private final long timeLimitNanos;
    private final SearchStack stack;
    private final TranspositionTable transpositionTable;
    private final long minThinkTimeNanos;

    private long lastVisitedNodes;
    private boolean lastTimedOut;

    public NegamaxAI(int maxDepth, Duration timeLimit) {
        this(maxDepth, timeLimit, Duration.ZERO, new TranspositionTable());
    }

    public NegamaxAI(int maxDepth, Duration timeLimit, TranspositionTable table) {
        this(maxDepth, timeLimit, Duration.ZERO, table);
    }

    public NegamaxAI(int maxDepth, Duration timeLimit, Duration minThinkTime) {
        this(maxDepth, timeLimit, minThinkTime, new TranspositionTable());
    }

    public NegamaxAI(int maxDepth, Duration timeLimit, Duration minThinkTime, TranspositionTable table) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("Depth must be at least 1");
        }
        Objects.requireNonNull(timeLimit, "timeLimit");
        Objects.requireNonNull(minThinkTime, "minThinkTime");
        Objects.requireNonNull(table, "table");
        this.maxDepth = maxDepth;
        long nanos = timeLimit.isZero() ? Long.MAX_VALUE : timeLimit.toNanos();
        this.timeLimitNanos = nanos <= 0L ? 1L : nanos;
        long minNanos = minThinkTime.isZero() ? 0L : minThinkTime.toNanos();
        if (minNanos < 0L) {
            throw new IllegalArgumentException("Minimum think time must be non-negative");
        }
        if (minNanos > timeLimitNanos) {
            throw new IllegalArgumentException("Minimum think time cannot exceed the overall time limit");
        }
        this.minThinkTimeNanos = minNanos;
        this.stack = new SearchStack();
        this.transpositionTable = table;
        CompletableFuture<Void> loadFuture = null;
        if (table.getPersistenceStatus() == TranspositionTable.PersistenceStatus.NOT_LOADED) {
            loadFuture = table.loadFromDiskAsync();
        }
        if (loadFuture != null) {
            loadFuture.whenComplete((ignored, error) -> {
                if (error != null) {
                    LOGGER.log(Level.WARNING, "Failed to load transposition table", error);
                }
            });
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                table.saveToDisk();
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "Failed to save transposition table during shutdown", ex);
            }
        }));
    }

    public int findBestMove(GameState state) {
        return findBestMove(state, maxDepth);
    }

    public int findBestMove(GameState state, int depthLimit) {
        Objects.requireNonNull(state, "state");

        if (depthLimit < 1) {
            throw new IllegalArgumentException("Depth limit must be at least 1");
        }

        if (state.isGameOver()) {
            throw new IllegalStateException("Cannot search moves in a terminal position");
        }

        stack.reset(state);
        long searchStart = System.nanoTime();
        long now = searchStart;
        long deadline = timeLimitNanos == Long.MAX_VALUE ? Long.MAX_VALUE : saturatingAdd(now, timeLimitNanos);
        stack.setDeadline(deadline);

        long boardBits = state.getBoard().getBits();
        long availableMoves = (~boardBits) & FULL_BOARD_MASK;
        long initialAvailable = availableMoves;
        int remainingMoves = Board.CELL_COUNT - state.getBoard().countBits();
        int boundedDepthLimit = Math.min(Math.min(maxDepth, depthLimit), remainingMoves);
        if (boundedDepthLimit < 1) {
            boundedDepthLimit = 1;
        }

        int bestMove = -1;
        int bestScore = Integer.MIN_VALUE;
        int alpha = Integer.MIN_VALUE / 2;
        int beta = Integer.MAX_VALUE / 2;
        int rootAlpha = alpha;

        while (availableMoves != 0) {
            int move = Long.numberOfTrailingZeros(availableMoves);
            availableMoves &= availableMoves - 1;

            long updatedBoard = boardBits | (1L << move);
            stack.push(move, boardBits, updatedBoard);
            int score = -negamax(updatedBoard, boundedDepthLimit - 1, -beta, -alpha);
            stack.pop();

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }

            if (stack.hasTimedOut()) {
                break;
            }

            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                break;
            }
        }

        if (bestMove < 0) {
            if (initialAvailable == 0) {
                throw new IllegalStateException("No legal moves available");
            }
            bestMove = Long.numberOfTrailingZeros(initialAvailable);
        }

        if (!stack.hasTimedOut() && bestScore != Integer.MIN_VALUE) {
            long key = computeKey(boardBits, stack.isFirstPlayerTurn());
            TTFlag flag;
            if (bestScore <= rootAlpha) {
                flag = TTFlag.UPPER_BOUND;
            } else if (bestScore >= beta) {
                flag = TTFlag.LOWER_BOUND;
            } else {
                flag = TTFlag.EXACT;
            }
            transpositionTable.put(key, new TTEntry(bestScore, boundedDepthLimit, flag));
        }

        lastVisitedNodes = stack.getVisitedNodes();
        lastTimedOut = stack.hasTimedOut();
        final int usedDepth = boundedDepthLimit;
        LOGGER.info(() -> String.format("Negamax explored %d nodes (depth=%d)", lastVisitedNodes, usedDepth));

        if (!stack.hasTimedOut()) {
            enforceMinimumThinkTime(searchStart);
        }

        if (!stack.hasTimedOut() && remainingMoves <= 1) {
            transpositionTable.saveToDiskAsync().whenComplete((ignored, error) -> {
                if (error != null) {
                    LOGGER.log(Level.WARNING, "Failed to save transposition table", error);
                }
            });
        }
        return bestMove;
    }

    public long getLastVisitedNodeCount() {
        return lastVisitedNodes;
    }

    public boolean wasLastSearchTimedOut() {
        return lastTimedOut;
    }

    private long saturatingAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private void enforceMinimumThinkTime(long searchStart) {
        if (minThinkTimeNanos <= 0L) {
            return;
        }
        long elapsed = System.nanoTime() - searchStart;
        long remaining = minThinkTimeNanos - elapsed;
        if (remaining <= 0L) {
            return;
        }
        LockSupport.parkNanos(remaining);
    }

    private int negamax(long boardBits, int depth, int alpha, int beta) {
        if (stack.isDeadlineExceeded()) {
            stack.markTimedOut();
            return stack.evaluateCurrent();
        }

        stack.incrementVisited();

        long key = computeKey(boardBits, stack.isFirstPlayerTurn());
        int originalAlpha = alpha;
        TTEntry cached = transpositionTable.get(key);
        if (cached != null && cached.depth() >= depth) {
            switch (cached.flag()) {
                case EXACT:
                    return cached.value();
                case LOWER_BOUND:
                    alpha = Math.max(alpha, cached.value());
                    break;
                case UPPER_BOUND:
                    beta = Math.min(beta, cached.value());
                    break;
                default:
                    break;
            }
            if (alpha >= beta) {
                return cached.value();
            }
        }

        if (depth <= 0 || boardBits == FULL_BOARD_MASK) {
            int evaluation = stack.evaluateCurrent();
            if (!stack.hasTimedOut()) {
                transpositionTable.put(key, new TTEntry(evaluation, Math.max(0, depth), TTFlag.EXACT));
            }
            return evaluation;
        }

        long available = (~boardBits) & FULL_BOARD_MASK;
        if (available == 0) {
            int evaluation = stack.evaluateCurrent();
            if (!stack.hasTimedOut()) {
                transpositionTable.put(key, new TTEntry(evaluation, Math.max(0, depth), TTFlag.EXACT));
            }
            return evaluation;
        }

        int bestValue = Integer.MIN_VALUE;

        while (available != 0) {
            int move = Long.numberOfTrailingZeros(available);
            available &= available - 1;

            long updated = boardBits | (1L << move);
            stack.push(move, boardBits, updated);
            int score = -negamax(updated, depth - 1, -beta, -alpha);
            stack.pop();

            if (score > bestValue) {
                bestValue = score;
            }
            if (stack.hasTimedOut()) {
                return bestValue;
            }

            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                break;
            }
        }

        if (bestValue == Integer.MIN_VALUE) {
            int evaluation = stack.evaluateCurrent();
            if (!stack.hasTimedOut()) {
                transpositionTable.put(key, new TTEntry(evaluation, Math.max(0, depth), TTFlag.EXACT));
            }
            return evaluation;
        }
        if (!stack.hasTimedOut()) {
            TTFlag flag;
            if (bestValue <= originalAlpha) {
                flag = TTFlag.UPPER_BOUND;
            } else if (bestValue >= beta) {
                flag = TTFlag.LOWER_BOUND;
            } else {
                flag = TTFlag.EXACT;
            }
            transpositionTable.put(key, new TTEntry(bestValue, depth, flag));
        }
        return bestValue;
    }

    private static final class SearchStack {

        private final long[] boards = new long[Board.CELL_COUNT + 1];
        private final boolean[] firstToMove = new boolean[Board.CELL_COUNT + 1];
        private final int[] firstScores = new int[Board.CELL_COUNT + 1];
        private final int[] secondScores = new int[Board.CELL_COUNT + 1];

        private int ply;
        private long visitedNodes;
        private boolean timedOut;
        private long deadline;

        void reset(GameState state) {
            ply = 0;
            boards[0] = state.getBoard().getBits();
            firstToMove[0] = state.getBoard().isFirstPlayer();
            firstScores[0] = state.getScore(true);
            secondScores[0] = state.getScore(false);
            visitedNodes = 0L;
            timedOut = false;
        }

        void setDeadline(long deadline) {
            this.deadline = deadline;
        }

        void push(int move, long previousBoard, long updatedBoard) {
            boolean isFirst = firstToMove[ply];
            int delta = ScoreCalculator.calculateScoreDelta(previousBoard, updatedBoard, move);

            int newFirstScore = firstScores[ply] + (isFirst ? delta : 0);
            int newSecondScore = secondScores[ply] + (isFirst ? 0 : delta);

            ply++;
            boards[ply] = updatedBoard;
            firstToMove[ply] = !isFirst;
            firstScores[ply] = newFirstScore;
            secondScores[ply] = newSecondScore;
        }

        void pop() {
            if (ply == 0) {
                throw new IllegalStateException("Cannot pop root state");
            }
            ply--;
        }

        int evaluateCurrent() {
            boolean firstPlayerTurn = firstToMove[ply];
            int diff = firstScores[ply] - secondScores[ply];
            int perspective = firstPlayerTurn ? diff : -diff;
            return perspective * SCORE_WEIGHT + bestPotential(boards[ply]);
        }

        void incrementVisited() {
            visitedNodes++;
        }

        long getVisitedNodes() {
            return visitedNodes;
        }

        boolean hasTimedOut() {
            return timedOut;
        }

        void markTimedOut() {
            timedOut = true;
        }

        boolean isDeadlineExceeded() {
            return timedOut || (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline);
        }

        boolean isFirstPlayerTurn() {
            return firstToMove[ply];
        }

        private int bestPotential(long board) {
            long available = (~board) & FULL_BOARD_MASK;
            int best = 0;
            while (available != 0) {
                int move = Long.numberOfTrailingZeros(available);
                available &= available - 1;
                long updated = board | (1L << move);
                int delta = ScoreCalculator.calculateScoreDelta(board, updated, move);
                if (delta > best) {
                    best = delta;
                }
            }
            return best;
        }
    }

    private long computeKey(long boardBits, boolean firstPlayerTurn) {
        long canonical = Symmetry.canonical(boardBits);
        long turnBit = firstPlayerTurn ? 1L : 0L;
        return (canonical << 1) | turnBit;
    }
}
