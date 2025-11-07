package com.honeycomb.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoardTest {

    @Test
    void emptyBoardHasNoBitsSet() {
        Board board = new Board();
        assertEquals(0, board.countBits());
        for (int i = 0; i < Board.CELL_COUNT; i++) {
            assertTrue(board.isEmpty(i));
        }
    }

    @Test
    void withCellSetsBit() {
        Board board = new Board();
        Board updated = board.withCell(10);
        assertTrue(board.isEmpty(10));
        assertFalse(updated.isEmpty(10));
        assertEquals(0, board.countBits());
        assertEquals(1, updated.countBits());
    }
}
