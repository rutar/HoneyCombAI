package com.honeycomb.visualizer.model;

import com.honeycomb.core.GameState;
import com.honeycomb.core.ai.SearchTelemetry;
import com.honeycomb.core.ai.TranspositionTable;
import java.util.Objects;

/**
 * Snapshot of a single position within a simulated Honeycomb match.
 */
public record GameFrame(
        GameState state,
        int lastMove,
        long firstPlayerBits,
        long secondPlayerBits,
        long visitedNodes,
        boolean timedOut,
        SearchTelemetry telemetry,
        TranspositionTable.UpdateEvent tableEvent,
        int tableSize,
        int ply) {

    public GameFrame {
        Objects.requireNonNull(state, "state");
        telemetry = telemetry == null ? SearchTelemetry.empty() : telemetry;
    }

    public static GameFrame initial(GameState state, TranspositionTable table) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(table, "table");
        return new GameFrame(state, -1, 0L, 0L, 0L, false, SearchTelemetry.empty(), table.getLastUpdate(),
                table.size(), state.getMoveNumber());
    }

    public boolean hasLastMove() {
        return lastMove >= 0;
    }

}
