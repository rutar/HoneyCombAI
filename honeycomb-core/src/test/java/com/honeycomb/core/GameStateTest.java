package com.honeycomb.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameStateTest {

    @Test
    void initialStateTracksNeutralCells() {
        GameState state = new GameState(0);
        assertEquals(0, state.getMoveNumber());
        assertEquals(0, state.getScore(true));
        assertEquals(0, state.getScore(false));
        assertEquals(1L, state.getCanonicalBoard());
        assertFalse(state.getBoard().isEmpty(state.getBoard().getBlockedCell()));
    }

    @Test
    void firstMoveDoesNotAwardNeutralPoints() {
        GameState state = new GameState(0);
        state = state.applyMove(1);
        assertEquals(0, state.getScore(true));
        assertEquals(0, state.getScore(false));
        assertEquals(1, state.getMoveNumber());
        assertNotEquals(0L, state.getCanonicalBoard());
    }

    @Test
    void playerCompletingLineGetsPoint() {
        GameState state = new GameState(0);
        state = state.applyMove(1); // Player 1
        state = state.applyMove(3); // Player 2, elsewhere
        state = state.applyMove(2); // Player 1 completes row 1
        assertEquals(2, state.getScore(true));
        assertEquals(0, state.getScore(false));
    }

    @Test
    void gameEndsAfterAllAvailableMoves() {
        GameState state = new GameState(0);
        Board board = state.getBoard();
        for (int i = 0; i < Board.CELL_COUNT; i++) {
            if (board.isBlockedCell(i)) {
                continue;
            }
            state = state.applyMove(i);
        }
        assertTrue(state.isGameOver());
        assertEquals(Board.PLAYABLE_CELL_COUNT, state.getMoveNumber());
        assertEquals(164, state.getScore(true) + state.getScore(false));
    }

    @Test
    void canonicalBoardReflectsSymmetricMoves() {
        GameState state = new GameState(0);
        assertEquals(1L, state.getCanonicalBoard());

        int move = -1;
        int symmetricMoveIndex = -1;
        Board board = state.getBoard();
        for (int cell = 0; cell < Board.CELL_COUNT; cell++) {
            if (board.isBlockedCell(cell)) {
                continue;
            }
            int rotated = Symmetry.PERMUTATIONS[1][cell];
            if (!board.isBlockedCell(rotated)) {
                move = cell;
                symmetricMoveIndex = rotated;
                break;
            }
        }

        assertTrue(move >= 0, "Expected to find a playable move pair");

        GameState rotatedState = new GameState(0).applyMove(move);
        long canonical = rotatedState.getCanonicalBoard();

        GameState symmetricState = new GameState(0).applyMove(symmetricMoveIndex);

        assertEquals(canonical, symmetricState.getCanonicalBoard());
    }
}
