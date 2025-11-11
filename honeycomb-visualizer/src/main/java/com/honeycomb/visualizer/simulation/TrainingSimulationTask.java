package com.honeycomb.visualizer.simulation;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import com.honeycomb.core.ScoreCalculator;
import com.honeycomb.core.ai.NegamaxAI;
import com.honeycomb.core.ai.TranspositionTable;
import com.honeycomb.visualizer.model.GameFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.concurrent.Task;

/**
 * Background task that runs a single self-play simulation using the {@link NegamaxAI}.
 */
public final class TrainingSimulationTask extends Task<List<GameFrame>> {

    private final NegamaxAI ai;
    private final TranspositionTable table;
    private final int depthLimit;

    private static final long[] LINE_MASKS = ScoreCalculator.getLineMasks();

    public TrainingSimulationTask(NegamaxAI ai, TranspositionTable table, int depthLimit) {
        this.ai = Objects.requireNonNull(ai, "ai");
        this.table = Objects.requireNonNull(table, "table");
        if (depthLimit < 1) {
            throw new IllegalArgumentException("Depth limit must be at least 1");
        }
        this.depthLimit = depthLimit;
    }

    @Override
    protected List<GameFrame> call() {
        updateMessage("Подготовка...");
        updateProgress(0, Board.CELL_COUNT);

        List<GameFrame> frames = new ArrayList<>();
        GameState state = new GameState();
        long firstBits = 0L;
        long secondBits = 0L;
        long firstLines = 0L;
        long secondLines = 0L;
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
            int move = ai.findBestMove(state, depthLimit);

            if (firstToMove) {
                firstBits |= (1L << move);
            } else {
                secondBits |= (1L << move);
            }


            int[] candidateLines = ScoreCalculator.getLinesForCell(move);
            for (int lineIndex : candidateLines) {
                long mask = LINE_MASKS[lineIndex];
                if (firstToMove) {
                    if ((firstBits & mask) == mask) {
                        firstLines |= (1L << lineIndex);
                    }
                } else if ((secondBits & mask) == mask) {
                    secondLines |= (1L << lineIndex);
                }
            }


            state = state.applyMove(move);
            frames.add(new GameFrame(
                    state,
                    move,
                    firstBits,
                    secondBits,
                    firstLines,
                    secondLines,

                    ai.getLastVisitedNodeCount(),
                    ai.wasLastSearchTimedOut(),
                    table.getLastUpdate(),
                    table.size(),
                    state.getMoveNumber()));
        }

        updateProgress(Board.CELL_COUNT, Board.CELL_COUNT);
        updateMessage("Симуляция завершена");
        return frames;
    }
}
