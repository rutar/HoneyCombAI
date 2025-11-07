package com.honeycomb.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility responsible for constructing all scoring lines on the Honeycomb board and for calculating
 * how many lines are completed by a newly placed stone.
 */
public final class ScoreCalculator {

    public static final int BOARD_HEIGHT = 10;
    public static final int TOTAL_LINES = 30;

    private static final long[] LINE_MASKS;
    private static final int[][] LINES_BY_CELL;

    static {
        int[][] linesByCell = new int[Board.CELL_COUNT][3];
        int[] lineCounts = new int[Board.CELL_COUNT];

        List<int[]> lines = new ArrayList<>();
        lines.addAll(generateHorizontalLines());
        lines.addAll(generateDiagonalLines(1, 1)); // ↘ direction
        lines.addAll(generateDiagonalLines(1, 0)); // ↙ direction

        long[] masks = new long[lines.size()];
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            int[] cells = lines.get(lineIndex);
            long mask = 0L;
            for (int cell : cells) {
                mask |= 1L << cell;
                int count = lineCounts[cell];
                if (count >= 3) {
                    throw new IllegalStateException("Cell " + cell + " belongs to more than three lines");
                }
                linesByCell[cell][count] = lineIndex;
                lineCounts[cell] = count + 1;
            }
            masks[lineIndex] = mask;
        }

        for (int cell = 0; cell < Board.CELL_COUNT; cell++) {
            if (lineCounts[cell] != 3) {
                throw new IllegalStateException("Cell " + cell + " belongs to " + lineCounts[cell]
                        + " lines instead of three");
            }
        }

        LINE_MASKS = masks;
        LINES_BY_CELL = linesByCell;
    }

    private ScoreCalculator() {
    }

    /**
     * Returns a defensive copy of all line bit masks.
     */
    public static long[] getLineMasks() {
        return LINE_MASKS.clone();
    }

    /**
     * Returns the three line indices that include the provided cell.
     */
    public static int[] getLinesForCell(int cellIndex) {
        int[] lines = LINES_BY_CELL[cellIndex];
        return new int[] {lines[0], lines[1], lines[2]};
    }

    /**
     * Calculates how many lines are completed by placing a stone on {@code cellIndex}. The method
     * expects the board states before and after the move.
     */
    public static int calculateScoreDelta(long previousBoard, long updatedBoard, int cellIndex) {
        int delta = 0;
        for (int lineIndex : LINES_BY_CELL[cellIndex]) {
            long mask = LINE_MASKS[lineIndex];
            if ((previousBoard & mask) != mask && (updatedBoard & mask) == mask) {
                delta+= Long.bitCount(mask);
            }
        }
        return delta;
    }

    private static List<int[]> generateHorizontalLines() {
        List<int[]> lines = new ArrayList<>();
        int index = 0;
        for (int row = 0; row < BOARD_HEIGHT; row++) {
            int length = row + 1;
            int[] line = new int[length];
            for (int col = 0; col < length; col++) {
                line[col] = index++;
            }
            lines.add(line);
        }
        return lines;
    }

    private static List<int[]> generateDiagonalLines(int rowStep, int colStep) {
        List<int[]> lines = new ArrayList<>();
        if (rowStep == 1 && colStep == 1) {
            // Start from the left border (column 0)
            for (int row = 0; row < BOARD_HEIGHT; row++) {
                lines.add(collectLine(row, 0, rowStep, colStep));
            }
        } else if (rowStep == 1 && colStep == 0) {
            // Start from the right border (column == row)
            for (int row = 0; row < BOARD_HEIGHT; row++) {
                lines.add(collectLine(row, row, rowStep, colStep));
            }
        } else {
            throw new IllegalArgumentException("Unsupported direction: " + rowStep + ", " + colStep);
        }
        return lines;
    }

    private static int[] collectLine(int startRow, int startCol, int rowStep, int colStep) {
        List<Integer> cells = new ArrayList<>();
        int row = startRow;
        int col = startCol;
        while (row < BOARD_HEIGHT && col >= 0 && col <= row) {
            cells.add(index(row, col));
            row += rowStep;
            col += colStep;
        }
        return cells.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int index(int row, int col) {
        int start = row * (row + 1) / 2;
        return start + col;
    }
}
