package com.honeycomb.core.ai;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Command line entry point for running {@link Trainer} self-play sessions with configurable
 * parameters.
 */
public final class TrainerRunner {

    private static final Logger LOGGER = Logger.getLogger(TrainerRunner.class.getName());

    private TrainerRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 5) {
            printUsage();
            return;
        }
        try {
            int gameCount = Integer.parseInt(args[0]);
            int maxDepth = Integer.parseInt(args[1]);
            long timeLimitMillis = Long.parseLong(args[2]);
            int scheduledDepth = args.length >= 4 ? Integer.parseInt(args[3]) : maxDepth;
            Path tablePath = args.length == 5 ? Paths.get(args[4]) : null;

            TranspositionTable table = tablePath == null ? new TranspositionTable() : new TranspositionTable(tablePath);
            table.loadFromDisk();

            Trainer trainer = new Trainer(table, maxDepth, Duration.ofMillis(timeLimitMillis),
                    Trainer.DepthScheduler.constant(scheduledDepth));
            trainer.playGames(gameCount);
        } catch (NumberFormatException ex) {
            LOGGER.log(Level.SEVERE, "Failed to parse arguments", ex);
            printUsage();
        } catch (IllegalArgumentException ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: TrainerRunner <gameCount> <maxDepth> <timeLimitMillis> [depthOverride] [tablePath]");
    }
}
