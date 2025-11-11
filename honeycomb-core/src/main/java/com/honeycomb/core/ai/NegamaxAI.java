package com.honeycomb.core.ai;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import com.honeycomb.core.ScoreCalculator;
import java.time.Duration;
import java.util.Objects;
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

    private long lastVisitedNodes;
    private boolean lastTimedOut;

    public NegamaxAI(int maxDepth, Duration timeLimit) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("Depth must be at least 1");
        }
        Objects.requireNonNull(timeLimit, "timeLimit");
        this.maxDepth = maxDepth;
        long nanos = timeLimit.isZero() ? Long.MAX_VALUE : timeLimit.toNanos();
        this.timeLimitNanos = nanos <= 0L ? 1L : nanos;
        this.stack = new SearchStack();
    }

    public int findBestMove(GameState state) {
        Objects.requireNonNull(state, "state");

        if (state.isGameOver()) {
            throw new IllegalStateException("Cannot search moves in a terminal position");
        }

        stack.reset(state);
        long now = System.nanoTime();
        long deadline = timeLimitNanos == Long.MAX_VALUE ? Long.MAX_VALUE : saturatingAdd(now, timeLimitNanos);
        stack.setDeadline(deadline);

        long boardBits = state.getBoard().getBits();
        long availableMoves = (~boardBits) & FULL_BOARD_MASK;
        long initialAvailable = availableMoves;
        int remainingMoves = Board.CELL_COUNT - state.getBoard().countBits();
        int depthLimit = Math.min(maxDepth, remainingMoves);

        int bestMove = -1;
        int bestScore = Integer.MIN_VALUE;
        int alpha = Integer.MIN_VALUE / 2;
        int beta = Integer.MAX_VALUE / 2;

        while (availableMoves != 0) {
            int move = Long.numberOfTrailingZeros(availableMoves);
            availableMoves &= availableMoves - 1;

            long updatedBoard = boardBits | (1L << move);
            stack.push(move, boardBits, updatedBoard);
            int score = -negamax(updatedBoard, depthLimit - 1, -beta, -alpha);
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

        lastVisitedNodes = stack.getVisitedNodes();
        lastTimedOut = stack.hasTimedOut();
        final int usedDepth = depthLimit;
        LOGGER.info(() -> String.format("Negamax explored %d nodes (depth=%d)", lastVisitedNodes, usedDepth));
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

    private int negamax(long boardBits, int depth, int alpha, int beta) {
        if (stack.isDeadlineExceeded()) {
            stack.markTimedOut();
            return stack.evaluateCurrent();
        }

        stack.incrementVisited();

        if (depth <= 0 || boardBits == FULL_BOARD_MASK) {
            return stack.evaluateCurrent();
        }

        long available = (~boardBits) & FULL_BOARD_MASK;
        if (available == 0) {
            return stack.evaluateCurrent();
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
            return stack.evaluateCurrent();
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
}
