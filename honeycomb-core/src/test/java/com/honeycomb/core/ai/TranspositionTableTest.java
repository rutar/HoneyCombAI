package com.honeycomb.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranspositionTableTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsEntryWithGreaterDepth() {
        TranspositionTable table = new TranspositionTable(tempDir.resolve("depth.tt"));

        table.put(7L, new TTEntry(5, 1, TTFlag.EXACT, -1));
        table.put(7L, new TTEntry(8, 3, TTFlag.LOWER_BOUND, 12));
        table.put(7L, new TTEntry(4, 2, TTFlag.UPPER_BOUND, 9));

        TTEntry entry = table.get(7L);
        assertNotNull(entry);
        assertEquals(8, entry.value());
        assertEquals(3, entry.depth());
        assertSame(TTFlag.LOWER_BOUND, entry.flag());
        assertEquals(12, entry.bestMove());
    }

    @Test
    void persistsEntriesToDisk() {
        Path file = tempDir.resolve("table.bin");

        TranspositionTable table = new TranspositionTable(file);
        table.put(21L, new TTEntry(13, 2, TTFlag.EXACT, 3));
        table.put(22L, new TTEntry(7, 1, TTFlag.UPPER_BOUND, -1));
        table.saveToDisk();

        TranspositionTable loaded = new TranspositionTable(file);
        loaded.loadFromDisk();

        assertEquals(2, loaded.size());
        assertNull(loaded.getLastUpdate());
        TTEntry entry = loaded.get(21L);
        assertNotNull(entry);
        assertEquals(13, entry.value());
        assertEquals(2, entry.depth());
        assertSame(TTFlag.EXACT, entry.flag());
        assertEquals(3, entry.bestMove());
    }

    @Test
    void exposesLastUpdateDetails() {
        TranspositionTable table = new TranspositionTable(tempDir.resolve("updates.tt"));

        table.put(5L, new TTEntry(3, 2, TTFlag.EXACT, 17));

        TranspositionTable.UpdateEvent first = table.getLastUpdate();
        assertNotNull(first);
        assertEquals(5L, first.key());
        assertSame(TTFlag.EXACT, first.entry().flag());
        assertEquals(17, first.entry().bestMove());
        assertNull(first.previousEntry());
        assertTrue(first.replaced());
        assertEquals(1, first.sizeAfterUpdate());

        table.put(5L, new TTEntry(9, 1, TTFlag.LOWER_BOUND, 4));

        TranspositionTable.UpdateEvent second = table.getLastUpdate();
        assertNotNull(second);
        assertEquals(5L, second.key());
        assertFalse(second.replaced(), "Entry should remain unchanged when depth is shallower");
        assertSame(first.entry(), second.entry());
        assertSame(first.entry(), second.previousEntry());
        assertEquals(17, second.entry().bestMove());
        assertEquals(1, second.sizeAfterUpdate());
    }

    @Test
    void reportsPersistenceStatusUpdates() {
        Path file = tempDir.resolve("status.tt");
        TranspositionTable table = new TranspositionTable(file);

        List<TranspositionTable.PersistenceStatus> statuses = new ArrayList<>();
        table.addPersistenceListener(statuses::add);

        table.loadFromDiskAsync().join();
        assertEquals(TranspositionTable.PersistenceStatus.READY, table.getPersistenceStatus());

        table.put(1L, new TTEntry(2, 1, TTFlag.EXACT, -1));
        table.saveToDiskAsync().join();
        assertEquals(TranspositionTable.PersistenceStatus.READY, table.getPersistenceStatus());

        assertIterableEquals(List.of(
                TranspositionTable.PersistenceStatus.LOADING,
                TranspositionTable.PersistenceStatus.READY,
                TranspositionTable.PersistenceStatus.SAVING,
                TranspositionTable.PersistenceStatus.READY), statuses);
    }

    @Test
    void reusesOngoingLoadAndSkipsDuplicateReads() {
        Path file = tempDir.resolve("dedupe.tt");
        TranspositionTable source = new TranspositionTable(file);
        source.put(1L, new TTEntry(3, 2, TTFlag.EXACT, 2));
        source.put(2L, new TTEntry(7, 1, TTFlag.LOWER_BOUND, 8));
        source.saveToDisk();

        TranspositionTable table = new TranspositionTable(file);

        CompletableFuture<Void> first = table.loadFromDiskAsync();
        CompletableFuture<Void> second = table.loadFromDiskAsync();

        assertSame(first, second, "Concurrent load requests should reuse the same task");

        first.join();

        CompletableFuture<Void> third = table.loadFromDiskAsync();
        assertNotSame(first, third, "Completed load should return an already completed future");
        assertTrue(third.isDone());
        third.join();

        assertEquals(2, table.size());
    }
}
