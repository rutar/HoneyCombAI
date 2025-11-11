package com.honeycomb.core.ai;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe transposition table that supports persistence across engine runs.
 */
public final class TranspositionTable {

    private static final Logger LOGGER = Logger.getLogger(TranspositionTable.class.getName());
    private static final String DEFAULT_FILE_NAME = "transposition-table.bin";
    private static final Path DEFAULT_PATH = Paths.get(System.getProperty("user.home"), ".honeycomb", DEFAULT_FILE_NAME);

    private final ConcurrentHashMap<Long, TTEntry> entries = new ConcurrentHashMap<>();
    private final Path storagePath;

    public TranspositionTable() {
        this(DEFAULT_PATH);
    }

    public TranspositionTable(Path storagePath) {
        this.storagePath = storagePath;
    }

    public TTEntry get(long key) {
        return entries.get(key);
    }

    public void put(long key, TTEntry entry) {
        entries.merge(key, entry, (existing, replacement) -> existing.depth() >= replacement.depth() ? existing : replacement);
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public void loadFromDisk() {
        loadFromDisk(storagePath);
    }

    public void saveToDisk() {
        saveToDisk(storagePath);
    }

    private void loadFromDisk(Path path) {
        if (path == null) {
            return;
        }
        if (!Files.exists(path)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            entries.clear();
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                long key = input.readLong();
                int value = input.readInt();
                int depth = input.readInt();
                byte flagOrdinal = input.readByte();
                TTFlag flag = TTFlag.values()[flagOrdinal];
                entries.put(key, new TTEntry(value, depth, flag));
            }
            LOGGER.info(() -> String.format("Loaded %d transposition entries from %s", count, path));
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Failed to load transposition table from " + path, ex);
        }
    }

    private void saveToDisk(Path path) {
        if (path == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to prepare directory for transposition table", ex);
            return;
        }

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            output.writeInt(entries.size());
            for (Map.Entry<Long, TTEntry> entry : entries.entrySet()) {
                output.writeLong(entry.getKey());
                TTEntry value = entry.getValue();
                output.writeInt(value.value());
                output.writeInt(value.depth());
                output.writeByte(value.flag().ordinal());
            }
            output.flush();
            LOGGER.info(() -> String.format("Saved %d transposition entries to %s", entries.size(), path));
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to save transposition table to " + path, ex);
        }
    }
}
