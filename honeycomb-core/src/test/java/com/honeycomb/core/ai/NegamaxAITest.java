package com.honeycomb.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NegamaxAITest {

    @Test
    void choosesImmediateScoringMove() {
        NegamaxAI ai = new NegamaxAI(2, Duration.ofMillis(10));
        GameState state = new GameState();

        int move = ai.findBestMove(state);

        assertEquals(0, move, "AI should prioritise the guaranteed scoring move on row 0");
    }

    @Test
    void recordsVisitedNodes() {
        NegamaxAI ai = new NegamaxAI(2, Duration.ofMillis(10));
        GameState state = new GameState();

        ai.findBestMove(state);

        assertTrue(ai.getLastVisitedNodeCount() > 0, "The search should inspect at least one node");
    }

    @Test
    void detectsTimeout() {
        NegamaxAI ai = new NegamaxAI(8, Duration.ofNanos(1));
        GameState state = new GameState();

        int move = ai.findBestMove(state);

        assertTrue(move >= 0 && move < Board.CELL_COUNT, "AI must return a legal move even on timeout");
        assertTrue(ai.wasLastSearchTimedOut(), "Search should report the timeout condition");
    }

    @Test
    void reusesTranspositionTableBetweenSearches() {
        NegamaxAI ai = new NegamaxAI(3, Duration.ofMillis(50));
        GameState state = new GameState();

        ai.findBestMove(state);
        long firstRunNodes = ai.getLastVisitedNodeCount();

        ai.findBestMove(state);
        long secondRunNodes = ai.getLastVisitedNodeCount();

        assertTrue(firstRunNodes > 0, "Search should visit at least one node");
        assertTrue(secondRunNodes <= firstRunNodes, "Transposition table should avoid exploring additional nodes");
    }
}
