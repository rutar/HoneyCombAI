package com.honeycomb.core.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TrainerTest {

    @Test
    void savesTranspositionTableAfterSelfPlayGame() throws Exception {
        Path tempDir = Files.createTempDirectory("trainer-test");
        Path tablePath = tempDir.resolve("tt.bin");
        TranspositionTable table = new TranspositionTable(tablePath);
        Trainer trainer = new Trainer(table, 3, Duration.ofMillis(5), Trainer.DepthScheduler.constant(2));
        try {
            trainer.playGames(1);

            assertTrue(Files.exists(tablePath), "Trainer should persist the transposition table after each game");
        } finally {
            Files.deleteIfExists(tablePath);
            Files.deleteIfExists(tempDir);
        }
    }
}

