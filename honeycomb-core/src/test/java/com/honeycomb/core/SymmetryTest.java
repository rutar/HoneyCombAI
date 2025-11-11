package com.honeycomb.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SymmetryTest {

    @Test
    void permutationsAreBijections() {
        for (int symmetry = 0; symmetry < Symmetry.SYMMETRY_COUNT; symmetry++) {
            boolean[] seen = new boolean[Board.CELL_COUNT];
            for (int cell = 0; cell < Board.CELL_COUNT; cell++) {
                int mapped = Symmetry.PERMUTATIONS[symmetry][cell];
                assertTrue(mapped >= 0 && mapped < Board.CELL_COUNT, "Mapped index out of range");
                seen[mapped] = true;
            }
            for (int cell = 0; cell < Board.CELL_COUNT; cell++) {
                assertTrue(seen[cell], "Symmetry " + symmetry + " does not cover cell " + cell);
            }
        }
    }

    @Test
    void canonicalValueIsSymmetryInvariant() {
        long board = 0L;
        board |= 1L << 0;
        board |= 1L << 5;
        board |= 1L << 14;
        board |= 1L << 30;
        board |= 1L << 40;

        long canonical = Symmetry.canonical(board);
        for (int symmetry = 0; symmetry < Symmetry.SYMMETRY_COUNT; symmetry++) {
            long transformed = Symmetry.apply(board, symmetry);
            assertEquals(canonical, Symmetry.canonical(transformed));
        }
    }

    @Test
    void canonicalIsMinimumAmongSymmetries() {
        long board = 0L;
        board |= 1L << 0;
        board |= 1L << 1;
        board |= 1L << 3;

        long min = Long.MAX_VALUE;
        for (int symmetry = 0; symmetry < Symmetry.SYMMETRY_COUNT; symmetry++) {
            long transformed = Symmetry.apply(board, symmetry);
            min = Math.min(min, transformed);
        }
        assertEquals(min, Symmetry.canonical(board));
    }
}
