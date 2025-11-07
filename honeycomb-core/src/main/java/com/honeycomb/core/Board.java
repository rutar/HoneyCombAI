package com.honeycomb.core;

/**
 * Bit-board representation of the Honeycomb game field. The board consists of 55 hexagonal cells
 * arranged in a triangular grid of height 10. Each cell is mapped to a bit position inside a long
 * value. Bit index 0 corresponds to the single cell in the top row and indices grow row by row
 * until index 54 in the bottom row.
 */
public class Board {

    public static final int CELL_COUNT = 55;

    private final long bits;

    /**
     * Creates an empty board.
     */
    public Board() {
        this(0L);
    }

    private Board(long bits) {
        this.bits = bits;
    }

    /**
     * Returns {@code true} if the cell with the provided index is empty.
     */
    public boolean isEmpty(int index) {
        checkIndex(index);
        return (bits & (1L << index)) == 0;
    }

    /**
     * Returns a new board instance with the cell at {@code index} marked as occupied.
     */
    public Board withCell(int index) {
        checkIndex(index);
        return new Board(bits | (1L << index));
    }

    /**
     * Returns the number of occupied cells on the board.
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

    private static void checkIndex(int index) {
        if (index < 0 || index >= CELL_COUNT) {
            throw new IllegalArgumentException("Cell index out of range: " + index);
        }
    }
}
