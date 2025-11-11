package com.honeycomb.core;

/**
 * Utility responsible for providing all six geometric symmetries of the triangular Honeycomb board.
 * The symmetries are represented as permutations of the 55 cell indices.
 */
public final class Symmetry {

    public static final int SYMMETRY_COUNT = 6;

    /**
     * Precomputed permutation table. The first index selects the symmetry, the second
     * index specifies the original cell, and the stored value is the mapped cell index.
     */
    public static final int[][] PERMUTATIONS = new int[SYMMETRY_COUNT][Board.CELL_COUNT];

    static {
        int[][] permutations = PERMUTATIONS;

        int size = ScoreCalculator.BOARD_HEIGHT;
        int maxCoordinate = size - 1;

        int[][] symmetryPermutations = new int[][]{
                {0, 1, 2}, // identity
                {1, 2, 0}, // rotation 120°
                {2, 0, 1}, // rotation 240°
                {0, 2, 1}, // reflection over vertical axis
                {2, 1, 0}, // reflection over diagonal axis
                {1, 0, 2}  // reflection over the remaining axis
        };

        for (int index = 0; index < Board.CELL_COUNT; index++) {
            int row = rowFromIndex(index);
            int col = index - triangularNumber(row);

            int x = maxCoordinate - row;
            int y = col;
            int z = row - col;

            int[] coords = {x, y, z};

            for (int s = 0; s < SYMMETRY_COUNT; s++) {
                int[] permutation = symmetryPermutations[s];
                int nx = coords[permutation[0]];
                int ny = coords[permutation[1]];
                int nz = coords[permutation[2]];

                int newRow = ny + nz;
                int newCol = ny;

                permutations[s][index] = index(newRow, newCol);
            }
        }
    }

    private Symmetry() {
    }

    /**
     * Returns the canonical representative for the provided board, defined as the minimal bitboard
     * under all six symmetries.
     */
    public static long canonical(long board) {
        long min = Long.MAX_VALUE;
        for (int symmetry = 0; symmetry < SYMMETRY_COUNT; symmetry++) {
            long transformed = apply(board, symmetry);
            if (transformed < min) {
                min = transformed;
            }
        }
        return min;
    }

    /**
     * Applies the specified symmetry permutation to the provided board bit mask.
     */
    public static long apply(long board, int symmetry) {
        if (symmetry < 0 || symmetry >= SYMMETRY_COUNT) {
            throw new IllegalArgumentException("Symmetry index out of range: " + symmetry);
        }

        long result = 0L;
        long remaining = board;
        while (remaining != 0L) {
            int bit = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            result |= 1L << PERMUTATIONS[symmetry][bit];
        }
        return result;
    }

    private static int rowFromIndex(int index) {
        int row = 0;
        int remaining = index;
        while (remaining >= row + 1) {
            remaining -= row + 1;
            row++;
        }
        return row;
    }

    private static int triangularNumber(int row) {
        return row * (row + 1) / 2;
    }

    private static int index(int row, int col) {
        return triangularNumber(row) + col;
    }
}
