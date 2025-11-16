package com.honeycomb.core.ai;

import com.honeycomb.core.GameState;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Facilitates self-play training games where the {@link NegamaxAI} competes against itself.
 * The trainer keeps track of the transposition table growth and the average score difference
 * across the simulated games, saving the table to disk after each completed match.
 */
public final class Trainer {

    private static final Logger LOGGER = Logger.getLogger(Trainer.class.getName());

    private final NegamaxAI ai;
    private final TranspositionTable transpositionTable;
    private final DepthScheduler depthScheduler;
    private final int maxDepth;

    private int gamesPlayed;
    private long cumulativeScoreDelta;

    public Trainer(int maxDepth, Duration timeLimit) {
        this(new TranspositionTable(), maxDepth, timeLimit, Duration.ZERO, DepthScheduler.constant(maxDepth));
    }

    public Trainer(int maxDepth, Duration timeLimit, Duration minThinkTime) {
        this(new TranspositionTable(), maxDepth, timeLimit, minThinkTime, DepthScheduler.constant(maxDepth));
    }

    public Trainer(TranspositionTable transpositionTable, int maxDepth, Duration timeLimit, DepthScheduler depthScheduler) {
        this(transpositionTable, maxDepth, timeLimit, Duration.ZERO, depthScheduler);
    }

    public Trainer(TranspositionTable transpositionTable, int maxDepth, Duration timeLimit,
            Duration minThinkTime, DepthScheduler depthScheduler) {
        Objects.requireNonNull(transpositionTable, "transpositionTable");
        Objects.requireNonNull(timeLimit, "timeLimit");
        Objects.requireNonNull(minThinkTime, "minThinkTime");
        Objects.requireNonNull(depthScheduler, "depthScheduler");
        if (maxDepth < 1) {
            throw new IllegalArgumentException("Maximum depth must be at least 1");
        }
        this.transpositionTable = transpositionTable;
        this.depthScheduler = depthScheduler;
        this.maxDepth = maxDepth;
        this.ai = new NegamaxAI(maxDepth, timeLimit, minThinkTime, transpositionTable);
    }

    public void playGames(int gameCount) {
        if (gameCount < 1) {
            throw new IllegalArgumentException("Game count must be at least 1");
        }
        for (int i = 0; i < gameCount; i++) {
            playSingleGame(i);
        }
    }

    public void setTracePvsEnabled(boolean enabled) {
        ai.setTracePvsEnabled(enabled);
    }

    private void playSingleGame(int gameIndex) {
        int requestedDepth = depthScheduler.depthForGame(gameIndex);
        if (requestedDepth < 1) {
            throw new IllegalArgumentException("Depth scheduler returned an invalid depth: " + requestedDepth);
        }
        int effectiveDepth = Math.min(maxDepth, requestedDepth);

        GameState state = new GameState();
        int beforeSize = transpositionTable.size();

        while (!state.isGameOver()) {
            int move = ai.findBestMove(state, effectiveDepth);
            state = state.applyMove(move);
        }

        gamesPlayed++;
        cumulativeScoreDelta += (long) state.getScore(true) - state.getScore(false);
        int afterSize = transpositionTable.size();
        int addedEntries = Math.max(0, afterSize - beforeSize);
        double averageScore = gamesPlayed == 0 ? 0.0 : (double) cumulativeScoreDelta / gamesPlayed;

        final int depth = effectiveDepth;
        final double average = averageScore;
        final int added = addedEntries;
        final int gameNumber = gamesPlayed;
        LOGGER.info(() -> String.format("Completed training game %d (depth=%d, addedEntries=%d, averageScore=%.2f)",
                gameNumber, depth, added, average));

        transpositionTable.saveToDiskAsync().whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.log(Level.WARNING, "Failed to save transposition table after training game", error);
            }
        });
    }

    public interface DepthScheduler {

        int depthForGame(int gameIndex);

        static DepthScheduler constant(int depth) {
            if (depth < 1) {
                throw new IllegalArgumentException("Depth must be at least 1");
            }
            return ignored -> depth;
        }
    }
}

