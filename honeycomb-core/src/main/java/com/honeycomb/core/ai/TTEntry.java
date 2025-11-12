package com.honeycomb.core.ai;

/**
 * Entry stored inside the transposition table.
 *
 * @param value    the evaluated value from the perspective of the side to move
 * @param depth    the remaining depth for which the value is valid
 * @param flag     the alpha-beta bound classification for the stored value
 * @param bestMove the move that produced {@link #value()}, or {@code -1} if unknown
 */
public record TTEntry(int value, int depth, TTFlag flag, int bestMove) {
}
