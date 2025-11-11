package com.honeycomb.core;

/**
 * Immutable representation of the current state of a Honeycomb match.
 * The state keeps track of the board occupancy, both players' scores,
 * and the current move number.
 */
public final class GameState {

    private final Board board;
    private final int scoresFirstPlayer;
    private final int scoresSecondPlayer;
    private final long canonicalBoard;

    /**
     * Creates the initial empty game state.
     */
    public GameState() {
        this(new Board(), 0, 0);
    }

    private GameState(Board board, int scoresFirstPlayer, int scoresSecondPlayer) {
        this.board = board;
        this.scoresFirstPlayer = scoresFirstPlayer;
        this.scoresSecondPlayer = scoresSecondPlayer;
        this.canonicalBoard = Symmetry.canonical(board.getBits());
    }

    /**
     * Returns the board associated with this state.
     */
    public Board getBoard() {
        return board;
    }

    /**
     * Returns the canonical bitboard representation of the current state, which normalizes
     * all symmetric positions to a single value.
     */
    public long getCanonicalBoard() {
        return canonicalBoard;
    }

    /**
     * Returns the score of the specified player.
     * @param firstPlayer true for the first player, false for the second.
     */
    public int getScore(boolean firstPlayer) {
        return firstPlayer ? scoresFirstPlayer : scoresSecondPlayer;
    }

    /**
     * Returns the move number starting from zero.
     */
    public int getMoveNumber() {
        return board.countBits();
    }

    /**
     * Returns {@code true} if all 55 cells are occupied.
     */
    public boolean isGameOver() {
        return board.isFull();
    }

    /**
     * Applies the provided move and returns the resulting state.
     */
    public GameState applyMove(int cellIndex) {
        if (!board.isEmpty(cellIndex)) {
            throw new IllegalArgumentException("Cell " + cellIndex + " is already occupied");
        }

        Board updatedBoard = board.withCell(cellIndex);
        int delta = ScoreCalculator.calculateScoreDelta(board.getBits(), updatedBoard.getBits(), cellIndex);

        boolean firstPlayerTurn = board.isFirstPlayer();  // assume true = first player
        int newScore1 = scoresFirstPlayer + (firstPlayerTurn ? delta : 0);
        int newScore2 = scoresSecondPlayer + (firstPlayerTurn ? 0 : delta);

        return new GameState(updatedBoard, newScore1, newScore2);
    }
}
