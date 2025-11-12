package com.honeycomb.core.ai.state;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import com.honeycomb.core.ScoreCalculator;

/**
 * Mutable search buffers that allow tree explorations without per-node allocations.
 */
public final class SearchState {

    private static final long FULL_BOARD_MASK = -1L >>> (64 - Board.CELL_COUNT);
    private static final int SLICE_LENGTH = Board.CELL_COUNT;
    private static final int MAX_PLY = Board.CELL_COUNT;

    private final long[] boards = new long[MAX_PLY + 1];
    private final boolean[] firstToMove = new boolean[MAX_PLY + 1];
    private final int[] firstScores = new int[MAX_PLY + 1];
    private final int[] secondScores = new int[MAX_PLY + 1];

    private final int[] moveCounts = new int[MAX_PLY + 1];
    private final int[] moves = new int[(MAX_PLY + 1) * SLICE_LENGTH];
    private final int[] moveDeltas = new int[(MAX_PLY + 1) * SLICE_LENGTH];

    private int ply;

    /**
     * Resets this state to mirror the provided {@link GameState}.
     */
    public void reset(GameState state) {
        initialize(state.getBoard().getBits(), state.getBoard().isFirstPlayer(),
                state.getScore(true), state.getScore(false));
    }

    /**
     * Initialises the buffers from raw board data.
     */
    public void initialize(long boardBits, boolean firstPlayerToMove, int firstScore, int secondScore) {
        ply = 0;
        boards[0] = boardBits;
        firstToMove[0] = firstPlayerToMove;
        firstScores[0] = firstScore;
        secondScores[0] = secondScore;
        moveCounts[0] = 0;
    }

    public int ply() {
        return ply;
    }

    public long currentBoard() {
        return boards[ply];
    }

    public long boardAt(int depth) {
        return boards[depth];
    }

    public boolean isFirstPlayerTurn() {
        return firstToMove[ply];
    }

    public boolean isFirstPlayerTurn(int depth) {
        return firstToMove[depth];
    }

    public int firstScore() {
        return firstScores[ply];
    }

    public int firstScore(int depth) {
        return firstScores[depth];
    }

    public int secondScore() {
        return secondScores[ply];
    }

    public int secondScore(int depth) {
        return secondScores[depth];
    }

    public boolean isTerminal() {
        return boards[ply] == FULL_BOARD_MASK;
    }

    public int generateMoves() {
        long board = boards[ply];
        long available = (~board) & FULL_BOARD_MASK;
        int count = 0;
        int base = ply * SLICE_LENGTH;
        while (available != 0) {
            int move = Long.numberOfTrailingZeros(available);
            long updated = board | (1L << move);
            int delta = ScoreCalculator.calculateScoreDelta(board, updated, move);
            moves[base + count] = move;
            moveDeltas[base + count] = delta;
            available &= available - 1;
            count++;
        }
        moveCounts[ply] = count;
        return count;
    }

    public int moveAt(int depth, int index) {
        return moves[depth * SLICE_LENGTH + index];
    }

    public int moveDeltaAt(int depth, int index) {
        return moveDeltas[depth * SLICE_LENGTH + index];
    }

    public int moveCount(int depth) {
        return moveCounts[depth];
    }

    public void pushGenerated(int depth, int index) {
        int base = depth * SLICE_LENGTH + index;
        pushInternal(moves[base], moveDeltas[base]);
    }

    public void push(int move) {
        long board = boards[ply];
        long updated = board | (1L << move);
        int delta = ScoreCalculator.calculateScoreDelta(board, updated, move);
        pushInternal(move, delta);
    }

    private void pushInternal(int move, int delta) {
        long previousBoard = boards[ply];
        long updatedBoard = previousBoard | (1L << move);
        boolean firstTurn = firstToMove[ply];
        int nextFirstScore = firstScores[ply] + (firstTurn ? delta : 0);
        int nextSecondScore = secondScores[ply] + (firstTurn ? 0 : delta);

        ply++;
        boards[ply] = updatedBoard;
        firstToMove[ply] = !firstTurn;
        firstScores[ply] = nextFirstScore;
        secondScores[ply] = nextSecondScore;
        moveCounts[ply] = 0;
    }

    public void pop() {
        if (ply == 0) {
            throw new IllegalStateException("Cannot pop root state");
        }
        ply--;
    }

    public int evaluateCurrent(int scoreWeight) {
        boolean firstTurn = firstToMove[ply];
        int diff = firstScores[ply] - secondScores[ply];
        int perspective = firstTurn ? diff : -diff;
        return perspective * scoreWeight + bestPotential();
    }

    private int bestPotential() {
        int depth = ply;
        int count = generateMoves();
        int base = depth * SLICE_LENGTH;
        int best = 0;
        for (int i = 0; i < count; i++) {
            int delta = moveDeltas[base + i];
            if (delta > best) {
                best = delta;
            }
        }
        return best;
    }
}
