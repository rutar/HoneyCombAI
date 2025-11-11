package com.honeycomb.visualizer.model;

import com.honeycomb.core.GameState;
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
        long firstPlayerLines,
        long secondPlayerLines,
        long visitedNodes,
        boolean timedOut,
        TranspositionTable.UpdateEvent tableEvent,
        int tableSize,
        int ply) {

    public GameFrame {
        Objects.requireNonNull(state, "state");
    }

    public static GameFrame initial(GameState state, TranspositionTable table) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(table, "table");
        return new GameFrame(state, -1, 0L, 0L, 0L, 0L, 0L, false, table.getLastUpdate(), table.size(), state.getMoveNumber());
    }

    public boolean hasLastMove() {
        return lastMove >= 0;
    }

}
