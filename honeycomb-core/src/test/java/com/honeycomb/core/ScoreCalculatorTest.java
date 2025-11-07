package com.honeycomb.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ScoreCalculatorTest {

    @Test
    void lineMasksCoverAllDirections() {
        long[] masks = ScoreCalculator.getLineMasks();
        assertEquals(ScoreCalculator.TOTAL_LINES, masks.length);

        long horizontalMask = maskForRow(3);
        assertTrue(Arrays.stream(masks).anyMatch(m -> m == horizontalMask));
    }

    @Test
    void eachCellBelongsToThreeLines() {
        for (int cell = 0; cell < Board.CELL_COUNT; cell++) {
            int[] lines = ScoreCalculator.getLinesForCell(cell);
            assertEquals(3, lines.length);
        }
    }

    @Test
    void completingLineAwardsPoint() {
        Board board = new Board();
        long before = board.getBits();
        Board after = board.withCell(0);
        int delta = ScoreCalculator.calculateScoreDelta(before, after.getBits(), 0);
        assertEquals(1, delta); // single-cell horizontal line
    }

    private long maskForRow(int row) {
        int start = row * (row + 1) / 2;
        return IntStream.range(0, row + 1)
                .mapToLong(col -> 1L << (start + col))
                .reduce(0L, (a, b) -> a | b);
    }
}
