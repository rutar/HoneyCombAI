package com.honeycomb.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranspositionTableTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsEntryWithGreaterDepth() {
        TranspositionTable table = new TranspositionTable(tempDir.resolve("depth.tt"));

        table.put(7L, new TTEntry(5, 1, TTFlag.EXACT));
        table.put(7L, new TTEntry(8, 3, TTFlag.LOWER_BOUND));
        table.put(7L, new TTEntry(4, 2, TTFlag.UPPER_BOUND));

        TTEntry entry = table.get(7L);
        assertNotNull(entry);
        assertEquals(8, entry.value());
        assertEquals(3, entry.depth());
        assertSame(TTFlag.LOWER_BOUND, entry.flag());
    }

    @Test
    void persistsEntriesToDisk() {
        Path file = tempDir.resolve("table.bin");

        TranspositionTable table = new TranspositionTable(file);
        table.put(21L, new TTEntry(13, 2, TTFlag.EXACT));
        table.put(22L, new TTEntry(7, 1, TTFlag.UPPER_BOUND));
        table.saveToDisk();

        TranspositionTable loaded = new TranspositionTable(file);
        loaded.loadFromDisk();

        assertEquals(2, loaded.size());
        TTEntry entry = loaded.get(21L);
        assertNotNull(entry);
        assertEquals(13, entry.value());
        assertEquals(2, entry.depth());
        assertSame(TTFlag.EXACT, entry.flag());
    }
}
