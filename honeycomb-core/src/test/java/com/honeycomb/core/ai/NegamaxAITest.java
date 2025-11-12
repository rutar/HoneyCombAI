package com.honeycomb.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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

    @Test
    void enforcesDepthOverrideValidation() {
        NegamaxAI ai = new NegamaxAI(4, Duration.ofMillis(10));
        GameState state = new GameState();

        assertThrows(IllegalArgumentException.class, () -> ai.findBestMove(state, 0));
    }

    @Test
    void enforcesMinimumThinkTime() {
        Duration minThinkTime = Duration.ofMillis(20);
        NegamaxAI ai = new NegamaxAI(2, Duration.ofMillis(100), minThinkTime);
        GameState state = new GameState();

        long start = System.nanoTime();
        ai.findBestMove(state);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMillis >= minThinkTime.toMillis(),
                "Search should respect the configured minimum think time");
    }

    @Test
    void fallsBackToSequentialModeWhenParallelRequested() {
        NegamaxAI ai = new NegamaxAI(2, Duration.ofMillis(10));
        GameState state = new GameState();

        SearchConstraints constraints = new SearchConstraints(2, Duration.ofMillis(10), SearchConstraints.SearchMode.PAR);
        SearchResult result = ai.search(state, constraints);

        assertTrue(result.move() >= 0 && result.move() < Board.CELL_COUNT,
                "Search should return a legal move when parallel mode is requested");
    }
}
