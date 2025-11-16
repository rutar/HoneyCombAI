package com.honeycomb.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameStateTest {

    @Test
    void initialStateTracksNeutralCells() {
        GameState state = new GameState();
        assertEquals(0, state.getMoveNumber());
        assertEquals(0, state.getScore(true));
        assertEquals(0, state.getScore(false));
        assertEquals(0L, state.getCanonicalBoard());
        for (int cell : Board.BLOCKED_CELLS) {
            assertFalse(state.getBoard().isEmpty(cell));
        }
    }

    @Test
    void firstMoveDoesNotAwardNeutralPoints() {
        GameState state = new GameState();
        state = state.applyMove(1);
        assertEquals(0, state.getScore(true));
        assertEquals(0, state.getScore(false));
        assertEquals(1, state.getMoveNumber());
        assertNotEquals(0L, state.getCanonicalBoard());
    }

    @Test
    void playerCompletingLineGetsPoint() {
        GameState state = new GameState();
        state = state.applyMove(1); // Player 1
        state = state.applyMove(3); // Player 2, elsewhere
        state = state.applyMove(2); // Player 1 completes row 1
        assertEquals(2, state.getScore(true));
        assertEquals(0, state.getScore(false));
    }

    @Test
    void gameEndsAfterAllAvailableMoves() {
        GameState state = new GameState();
        for (int i = 0; i < Board.CELL_COUNT; i++) {
            if (Board.isBlockedCell(i)) {
                continue;
            }
            state = state.applyMove(i);
        }
        assertTrue(state.isGameOver());
        assertEquals(Board.PLAYABLE_CELL_COUNT, state.getMoveNumber());
        assertEquals(162, state.getScore(true) + state.getScore(false));
    }

    @Test
    void canonicalBoardReflectsSymmetricMoves() {
        GameState state = new GameState();
        assertEquals(0L, state.getCanonicalBoard());

        int move = -1;
        int symmetricMoveIndex = -1;
        for (int cell = 0; cell < Board.CELL_COUNT; cell++) {
            if (Board.isBlockedCell(cell)) {
                continue;
            }
            int rotated = Symmetry.PERMUTATIONS[1][cell];
            if (!Board.isBlockedCell(rotated)) {
                move = cell;
                symmetricMoveIndex = rotated;
                break;
            }
        }

        assertTrue(move >= 0, "Expected to find a playable move pair");

        GameState rotatedState = new GameState().applyMove(move);
        long canonical = rotatedState.getCanonicalBoard();

        GameState symmetricState = new GameState().applyMove(symmetricMoveIndex);

        assertEquals(canonical, symmetricState.getCanonicalBoard());
    }
}
