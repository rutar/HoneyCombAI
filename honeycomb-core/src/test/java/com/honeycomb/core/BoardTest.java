package com.honeycomb.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoardTest {

    @Test
    void initialBoardHasBlockedCells() {
        Board board = new Board();
        assertEquals(Board.BLOCKED_CELL_COUNT, board.countBits());
        for (int i = 0; i < Board.CELL_COUNT; i++) {
            if (Board.isBlockedCell(i)) {
                assertFalse(board.isEmpty(i));
            } else {
                assertTrue(board.isEmpty(i));
            }
        }
    }

    @Test
    void withCellSetsBit() {
        Board board = new Board();
        Board updated = board.withCell(10);
        assertTrue(board.isEmpty(10));
        assertFalse(updated.isEmpty(10));
        assertEquals(Board.BLOCKED_CELL_COUNT, board.countBits());
        assertEquals(Board.BLOCKED_CELL_COUNT + 1, updated.countBits());
    }
}
