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
        if (args.length < 3 || args.length > 6) {
            printUsage();
            return;
        }
        try {
            int gameCount = Integer.parseInt(args[0]);
            int maxDepth = Integer.parseInt(args[1]);
            long timeLimitMillis = Long.parseLong(args[2]);
            int scheduledDepth = maxDepth;
            long minThinkMillis = 0L;
            Path tablePath = null;
            boolean tracePvs = false;

            int index = 3;
            if (index < args.length && !args[index].startsWith("--")) {
                scheduledDepth = Integer.parseInt(args[index]);
                index++;
            }

            while (index < args.length) {
                String option = args[index];
                if (option.startsWith("--minThinkMillis=")) {
                    String value = option.substring("--minThinkMillis=".length());
                    minThinkMillis = Long.parseLong(value);
                } else if (option.startsWith("--table=")) {
                    if (tablePath != null) {
                        throw new IllegalArgumentException("Table path specified more than once");
                    }
                    tablePath = Paths.get(option.substring("--table=".length()));
                } else if ("--tracePVS".equals(option)) {
                    tracePvs = true;
                } else if (tablePath == null) {
                    tablePath = Paths.get(option);
                } else {
                    throw new IllegalArgumentException("Unrecognised argument: " + option);
                }
                index++;
            }

            if (minThinkMillis < 0L) {
                throw new IllegalArgumentException("minThinkMillis must be non-negative");
            }

            TranspositionTable table = tablePath == null ? new TranspositionTable() : new TranspositionTable(tablePath);
            table.loadFromDisk();

            Trainer trainer = new Trainer(table, maxDepth, Duration.ofMillis(timeLimitMillis),
                    Duration.ofMillis(minThinkMillis), Trainer.DepthScheduler.constant(scheduledDepth));
            trainer.setTracePvsEnabled(tracePvs);
            trainer.playGames(gameCount);
        } catch (NumberFormatException ex) {
            LOGGER.log(Level.SEVERE, "Failed to parse arguments", ex);
            printUsage();
        } catch (IllegalArgumentException ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
    }

    private static void printUsage() {
        System.err.println(
                "Usage: TrainerRunner <gameCount> <maxDepth> <timeLimitMillis> [depthOverride] "
                        + "[--minThinkMillis=<value>] [--table=<path>|tablePath] [--tracePVS]");
    }
}
