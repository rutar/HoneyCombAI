package com.honeycomb.core;

/**
 * Immutable representation of the current state of a Honeycomb match. The state keeps track of the
 * board occupancy, the current scores of both players and the move number.
 */
public final class GameState {

    private final Board board;
    private final int[] scores;
    private final int moveNumber;

    /**
     * Creates the initial empty game state.
     */
    public GameState() {
        this(new Board(), new int[] {0, 0}, 0);
    }

    private GameState(Board board, int[] scores, int moveNumber) {
        this.board = board;
        this.scores = scores;
        this.moveNumber = moveNumber;
    }

    /**
     * Returns the board associated with this state.
     */
    public Board getBoard() {
        return board;
    }

    /**
     * Returns the score of the specified player (0 for the first player, 1 for the second).
     */
    public int getScore(int player) {
        if (player < 0 || player > 1) {
            throw new IllegalArgumentException("Player index must be 0 or 1: " + player);
        }
        return scores[player];
    }

    /**
     * Returns the move number starting from zero.
     */
    public int getMoveNumber() {
        return moveNumber;
    }

    /**
     * Returns the index of the player that has to make the next move (0 or 1).
     */
    public int getCurrentPlayer() {
        return moveNumber % 2;
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
        int player = getCurrentPlayer();
        int delta = ScoreCalculator.calculateScoreDelta(board.getBits(), updatedBoard.getBits(), cellIndex);
        int[] updatedScores = new int[] {scores[0], scores[1]};
        updatedScores[player] += delta;
        return new GameState(updatedBoard, updatedScores, moveNumber + 1);
    }
}
