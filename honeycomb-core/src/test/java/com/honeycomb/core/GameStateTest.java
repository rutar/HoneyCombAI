package com.honeycomb.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameStateTest {

    @Test
    void firstMoveScoresSingleCellLine() {
        GameState state = new GameState();
        state = state.applyMove(0);
        assertEquals(1, state.getScore(0));
        assertEquals(0, state.getScore(1));
        assertEquals(1, state.getMoveNumber());
    }

    @Test
    void playerCompletingLineGetsPoint() {
        GameState state = new GameState();
        state = state.applyMove(1); // Player 1
        state = state.applyMove(3); // Player 2, elsewhere
        state = state.applyMove(2); // Player 1 completes row 1
        assertEquals(2, state.getScore(0));
        assertEquals(0, state.getScore(1));
    }

    @Test
    void gameEndsAfterFiftyFiveMoves() {
        GameState state = new GameState();
        for (int i = 0; i < Board.CELL_COUNT; i++) {
            state = state.applyMove(i);
        }
        assertTrue(state.isGameOver());
        assertEquals(Board.CELL_COUNT, state.getMoveNumber());
        assertEquals(165, state.getScore(0) + state.getScore(1));
    }
}
