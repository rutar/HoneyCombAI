package com.honeycomb.core.ai;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;

/**
 * Thread-safe transposition table that supports persistence across engine runs.
 */
public final class TranspositionTable {

    private static final Logger LOGGER = Logger.getLogger(TranspositionTable.class.getName());
    private static final String DEFAULT_FILE_NAME = "transposition-table.bin";
    private static final Path DEFAULT_PATH = Paths.get(System.getProperty("user.home"), ".honeycomb", DEFAULT_FILE_NAME);
    private static final CompletableFuture<Void> COMPLETED_LOAD = CompletableFuture.completedFuture(null);

    private final ConcurrentHashMap<Long, TTEntry> entries = new ConcurrentHashMap<>();
    private final Path storagePath;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "transposition-table-io");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<PersistenceStatus>> listeners = new CopyOnWriteArrayList<>();
    private volatile UpdateEvent lastUpdate;
    private volatile PersistenceStatus persistenceStatus = PersistenceStatus.NOT_LOADED;
    private final Object loadLock = new Object();
    private CompletableFuture<Void> loadFuture;

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
        Objects.requireNonNull(entry, "entry");
        PutContext context = new PutContext();
        entries.compute(key, (ignored, existing) -> {
            if (existing == null) {
                context.previous = null;
                context.stored = entry;
                context.replaced = true;
                return entry;
            }
            if (existing.depth() >= entry.depth()) {
                context.previous = existing;
                context.stored = existing;
                context.replaced = false;
                return existing;
            }
            context.previous = existing;
            context.stored = entry;
            context.replaced = true;
            return entry;
        });
        lastUpdate = new UpdateEvent(key, context.stored, context.previous, context.replaced, size());
    }

    public void clear() {
        entries.clear();
        lastUpdate = null;
    }

    public int size() {
        return entries.size();
    }

    public UpdateEvent getLastUpdate() {
        return lastUpdate;
    }

    public void loadFromDisk() {
        loadFromDiskAsync().join();
    }

    public void saveToDisk() {
        saveToDiskAsync().join();
    }

    public CompletableFuture<Void> loadFromDiskAsync() {
        synchronized (loadLock) {
            if (loadFuture != null) {
                return loadFuture;
            }
            if (persistenceStatus == PersistenceStatus.READY) {
                return COMPLETED_LOAD;
            }
            CompletableFuture<Void> future = submitPersistenceTask(PersistenceStatus.LOADING, () -> loadFromDiskInternal(storagePath));
            loadFuture = future;
            future.whenComplete((ignored, error) -> {
                synchronized (loadLock) {
                    if (loadFuture == future) {
                        loadFuture = null;
                    }
                }
            });
            return future;
        }
    }

    public CompletableFuture<Void> saveToDiskAsync() {
        return submitPersistenceTask(PersistenceStatus.SAVING, () -> saveToDiskInternal(storagePath));
    }

    public PersistenceStatus getPersistenceStatus() {
        return persistenceStatus;
    }

    public void addPersistenceListener(Consumer<PersistenceStatus> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removePersistenceListener(Consumer<PersistenceStatus> listener) {
        listeners.remove(listener);
    }

    private CompletableFuture<Void> submitPersistenceTask(PersistenceStatus runningStatus, Runnable action) {
        Objects.requireNonNull(runningStatus, "runningStatus");
        Objects.requireNonNull(action, "action");
        return CompletableFuture.runAsync(() -> {
            setPersistenceStatus(runningStatus);
            try {
                action.run();
                setPersistenceStatus(PersistenceStatus.READY);
            } catch (RuntimeException ex) {
                setPersistenceStatus(PersistenceStatus.NOT_LOADED);
                throw new CompletionException(ex);
            }
        }, ioExecutor);
    }

    private void loadFromDiskInternal(Path path) {
        if (path == null) {
            return;
        }
        if (!Files.exists(path)) {
            return;
        }
        long fileSize;
        try {
            fileSize = Files.size(path);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to read transposition table size", ex);
            throw new RuntimeException(ex);
        }
        long entrySizeV1 = Long.BYTES + Integer.BYTES + Integer.BYTES + 1;
        long entrySizeV2 = entrySizeV1 + Integer.BYTES;

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            entries.clear();
            lastUpdate = null;
            int count = input.readInt();
            long expectedV1 = Integer.BYTES + (long) count * entrySizeV1;
            long expectedV2 = Integer.BYTES + (long) count * entrySizeV2;
            boolean hasBestMove = fileSize == expectedV2;
            boolean warnedAboutSize = false;
            for (int i = 0; i < count; i++) {
                long key = input.readLong();
                int value = input.readInt();
                int depth = input.readInt();
                byte flagOrdinal = input.readByte();
                TTFlag flag = TTFlag.values()[flagOrdinal];
                int bestMove = -1;
                if (hasBestMove) {
                    bestMove = input.readInt();
                } else if (fileSize != expectedV1 && !warnedAboutSize) {
                    LOGGER.log(Level.WARNING, "Unexpected transposition table size: {0} bytes", fileSize);
                    warnedAboutSize = true;
                }
                entries.put(key, new TTEntry(value, depth, flag, bestMove));
            }
            LOGGER.info(() -> String.format("Loaded %d transposition entries from %s", count, path));
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Failed to load transposition table from " + path, ex);
            throw new RuntimeException(ex);
        }
    }

    private void saveToDiskInternal(Path path) {
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
            throw new RuntimeException(ex);
        }

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            output.writeInt(entries.size());
            for (Map.Entry<Long, TTEntry> entry : entries.entrySet()) {
                output.writeLong(entry.getKey());
                TTEntry value = entry.getValue();
                output.writeInt(value.value());
                output.writeInt(value.depth());
                output.writeByte(value.flag().ordinal());
                output.writeInt(value.bestMove());
            }
            output.flush();
            LOGGER.info(() -> String.format("Saved %d transposition entries to %s", entries.size(), path));
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to save transposition table to " + path, ex);
            throw new RuntimeException(ex);
        }
    }

    public record UpdateEvent(long key, TTEntry entry, TTEntry previousEntry, boolean replaced, int sizeAfterUpdate) {
    }

    public enum PersistenceStatus {
        NOT_LOADED,
        LOADING,
        SAVING,
        READY
    }

    private void setPersistenceStatus(PersistenceStatus status) {
        persistenceStatus = status;
        for (Consumer<PersistenceStatus> listener : listeners) {
            try {
                listener.accept(status);
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "Persistence listener failed", ex);
            }
        }
    }

    private static final class PutContext {

        private TTEntry stored;
        private TTEntry previous;
        private boolean replaced;
    }
}
