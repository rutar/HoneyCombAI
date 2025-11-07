package com.honeycomb.core;

/**
 * Immutable bitboard representation of the Honeycomb game field.
 * Each cell corresponds to a bit in a 55-bit long value.
 * The board also tracks which player's turn it is.
 */
public final class Board {

    public static final int CELL_COUNT = 55;

    private final long bits;
    private final boolean firstPlayer; // true = first player's turn, false = second player's turn

    /**
     * Creates an empty board with the first player to move.
     */
    public Board() {
        this(0L, true);
    }

    private Board(long bits, boolean firstPlayer) {
        this.bits = bits;
        this.firstPlayer = firstPlayer;
    }

    /**
     * Returns {@code true} if the cell with the provided index is empty.
     */
    public boolean isEmpty(int index) {
        checkIndex(index);
        return (bits & (1L << index)) == 0;
    }

    /**
     * Returns a new Board instance with the specified cell marked as occupied.
     * Automatically toggles the active player.
     */
    public Board withCell(int index) {
        checkIndex(index);
        if (!isEmpty(index)) {
            throw new IllegalArgumentException("Cell " + index + " is already occupied");
        }

        long updatedBits = bits | (1L << index);
        boolean nextPlayer = !firstPlayer; // invert turn
        return new Board(updatedBits, nextPlayer);
    }

    /**
     * Returns the number of occupied cells.
     */
    public int countBits() {
        return Long.bitCount(bits);
    }

    /**
     * Returns the raw bit representation of the board.
     */
    public long getBits() {
        return bits;
    }

    /**
     * Returns {@code true} if all 55 cells are occupied.
     */
    public boolean isFull() {
        return countBits() == CELL_COUNT;
    }

    /**
     * Returns {@code true} if it is currently the first player's turn.
     */
    public boolean isFirstPlayer() {
        return firstPlayer;
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= CELL_COUNT) {
            throw new IllegalArgumentException("Cell index out of range: " + index);
        }
    }
}
