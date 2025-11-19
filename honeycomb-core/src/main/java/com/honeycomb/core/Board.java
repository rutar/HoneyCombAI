package com.honeycomb.core;

/**
 * Immutable bitboard representation of the Honeycomb game field.
 * Each cell corresponds to a bit in a 55-bit long value.
 * The board also tracks which player's turn it is.
 */
public final class Board {

    public static final int CELL_COUNT = 55;
    public static final int[] CORNER_CELLS = {0, 45, 54};
    public static final int BLOCKED_CELL_COUNT = 1;
    public static final int PLAYABLE_CELL_COUNT = CELL_COUNT - BLOCKED_CELL_COUNT;

    private final long bits;
    private final long blockedCellsMask;
    private final int blockedCell;
    private final boolean firstPlayer; // true = first player's turn, false = second player's turn

    /**
     * Creates the initial board with a random neutral corner cell marked as unavailable and the
     * first player to move.
     */
    public Board() {
        this(selectRandomCornerCell());
    }

    /**
     * Creates the initial board with the provided corner cell blocked for both players.
     */
    public Board(int blockedCellIndex) {
        this(maskForBlockedCell(blockedCellIndex), blockedCellIndex, true);
    }

    private Board(long bits, long blockedCellsMask, int blockedCell, boolean firstPlayer) {
        this.bits = bits;
        this.blockedCellsMask = blockedCellsMask;
        this.blockedCell = blockedCell;
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
        if (isBlockedCell(index)) {
            throw new IllegalArgumentException("Cell " + index + " is blocked");
        }
        if (!isEmpty(index)) {
            throw new IllegalArgumentException("Cell " + index + " is already occupied");
        }

        long updatedBits = bits | (1L << index);
        boolean nextPlayer = !firstPlayer; // invert turn
        return new Board(updatedBits, blockedCellsMask, blockedCell, nextPlayer);
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

    /**
     * Returns {@code true} if the provided cell index is one of the neutral blocked cells.
     */
    public boolean isBlockedCell(int index) {
        checkIndex(index);
        return (blockedCellsMask & (1L << index)) != 0;
    }

    /**
     * Returns the mask that encodes the blocked cell on this board instance.
     */
    public long getBlockedCellsMask() {
        return blockedCellsMask;
    }

    /**
     * Returns the blocked corner cell index.
     */
    public int getBlockedCell() {
        return blockedCell;
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= CELL_COUNT) {
            throw new IllegalArgumentException("Cell index out of range: " + index);
        }
    }

    private static int selectRandomCornerCell() {
        int random = java.util.concurrent.ThreadLocalRandom.current().nextInt(CORNER_CELLS.length);
        return CORNER_CELLS[random];
    }

    private static long maskForBlockedCell(int blockedCellIndex) {
        for (int corner : CORNER_CELLS) {
            if (corner == blockedCellIndex) {
                return 1L << blockedCellIndex;
            }
        }
        throw new IllegalArgumentException("Blocked cell must be a corner: " + blockedCellIndex);
    }
}
