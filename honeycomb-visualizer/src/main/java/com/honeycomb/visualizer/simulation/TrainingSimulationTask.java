package com.honeycomb.visualizer.simulation;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import com.honeycomb.core.ai.SearchConstraints;
import com.honeycomb.core.ai.SearchResult;
import com.honeycomb.core.ai.Searcher;
import com.honeycomb.core.ai.TranspositionTable;
import com.honeycomb.visualizer.model.GameFrame;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.concurrent.Task;

/**
 * Background task that runs a single self-play simulation using a {@link Searcher}.
 */
public final class TrainingSimulationTask extends Task<List<GameFrame>> {

    private final Searcher searcher;
    private final TranspositionTable table;
    private final int depthLimit;
    private final Duration timeLimit;
    private final SearchConstraints.SearchMode mode;
    private final Consumer<GameFrame> frameListener;

    public TrainingSimulationTask(Searcher searcher, TranspositionTable table, int depthLimit,
            Duration timeLimit, SearchConstraints.SearchMode mode, Consumer<GameFrame> frameListener) {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.table = Objects.requireNonNull(table, "table");
        if (depthLimit < 1) {
            throw new IllegalArgumentException("Depth limit must be at least 1");
        }
        this.depthLimit = depthLimit;
        this.timeLimit = Objects.requireNonNull(timeLimit, "timeLimit");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.frameListener = Objects.requireNonNull(frameListener, "frameListener");
    }

    @Override
    protected List<GameFrame> call() {
        boolean onFxThread;
        try {
            onFxThread = Platform.isFxApplicationThread();
        } catch (IllegalStateException ex) {
            onFxThread = false;
        }
        if (onFxThread) {
            throw new IllegalStateException("Search must not run on the JavaFX application thread");
        }

        updateMessage("Подготовка...");
        updateProgress(0, Board.CELL_COUNT);

        List<GameFrame> frames = new ArrayList<>();
        GameState state = new GameState();
        long firstBits = 0L;
        long secondBits = 0L;
        frames.add(GameFrame.initial(state, table));

        while (!state.isGameOver()) {
            if (isCancelled()) {
                updateMessage("Остановлено");
                return frames;
            }

            int moveNumber = state.getMoveNumber() + 1;
            updateMessage(String.format("Поиск хода %d", moveNumber));
            updateProgress(state.getMoveNumber(), Board.CELL_COUNT);

            boolean firstToMove = state.getBoard().isFirstPlayer();
            SearchConstraints constraints = new SearchConstraints(depthLimit, timeLimit, mode);
            SearchResult result = searcher.search(state, constraints);
            int move = result.move();

            if (firstToMove) {
                firstBits |= (1L << move);
            } else {
                secondBits |= (1L << move);
            }

            state = state.applyMove(move);
            GameFrame frame = new GameFrame(
                    state,
                    move,
                    firstBits,
                    secondBits,
                    result.visitedNodes(),
                    result.timedOut(),
                    result.telemetry(),
                    table.getLastUpdate(),
                    table.size(),
                    state.getMoveNumber());
            frames.add(frame);
            publishFrame(frame);
            updateProgress(state.getMoveNumber(), Board.CELL_COUNT);
            updateMessage(String.format("Ход %d найден", moveNumber));
        }

        updateProgress(Board.CELL_COUNT, Board.CELL_COUNT);
        updateMessage("Симуляция завершена");
        return frames;
    }

    private void publishFrame(GameFrame frame) {
        if (frameListener == null) {
            return;
        }
        Platform.runLater(() -> frameListener.accept(frame));
    }
}
