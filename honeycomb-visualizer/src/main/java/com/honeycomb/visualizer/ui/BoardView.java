package com.honeycomb.visualizer.ui;

import com.honeycomb.core.Board;
import com.honeycomb.core.ScoreCalculator;
import com.honeycomb.visualizer.model.GameFrame;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Visual representation of the Honeycomb playing field.
 */
public final class BoardView extends Pane {

    private static final int BOARD_HEIGHT = 10;
    private static final double HEX_SIZE = 24.0;
    private static final double VERTICAL_SPACING = HEX_SIZE * 1.5;
    private static final double HORIZONTAL_SPACING = HEX_SIZE * Math.sqrt(3);
    private static final Paint EMPTY_FILL = Color.rgb(235, 238, 245);
    private static final Paint GRID_STROKE = Color.rgb(170, 177, 189);
    private static final Paint FIRST_PLAYER_FILL = Color.web("#4F6BED");
    private static final Paint SECOND_PLAYER_FILL = Color.web("#E57373");
    private static final Paint AVAILABLE_FILL = Color.web("#E3E9FF");
    private static final Paint FIRST_LINE_COLOR = Color.web("#243A9A");
    private static final Paint SECOND_LINE_COLOR = Color.web("#B71C1C");
    private static final Paint LAST_MOVE_FILL = Color.web("#FFB300", 0.45);
    private static final double DEFAULT_STROKE_WIDTH = 1.0;
    private static final double LINE_THICKNESS = 6.0;
    private static final long FULL_BOARD_MASK = -1L >>> (64 - Board.CELL_COUNT);
    private static final long[] LINE_MASKS = ScoreCalculator.getLineMasks();

    private final Polygon[] cells = new Polygon[Board.CELL_COUNT];
    private final double[] centerX = new double[Board.CELL_COUNT];
    private final double[] centerY = new double[Board.CELL_COUNT];
    private final Shape[] lineOverlays = new Shape[ScoreCalculator.TOTAL_LINES];
    private final Paint[] lineOwners = new Paint[ScoreCalculator.TOTAL_LINES];
    private final Map<Integer, List<Integer>> linesCompletedByPly = new HashMap<>();
    private final Group boardGroup;
    private final Group lineGroup;
    private final Circle lastMoveMarker;
    private double contentWidth;
    private double contentHeight;
    private long currentBoardBits;
    private int currentPly = -1;

    public BoardView() {
        setPadding(new Insets(16));
        setStyle("-fx-background-color: linear-gradient(to bottom, #fdfdfd, #e7ebf5);");
        boardGroup = new Group();
        getChildren().add(boardGroup);

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (int row = 0; row < BOARD_HEIGHT; row++) {
            for (int col = 0; col <= row; col++) {
                int index = index(row, col);
                double centerX = (col - row / 2.0) * HORIZONTAL_SPACING;
                double centerY = row * VERTICAL_SPACING;
                Polygon hex = createHexagon(centerX, centerY, HEX_SIZE);
                hex.setFill(EMPTY_FILL);
                hex.setStroke(GRID_STROKE);
                hex.setStrokeWidth(DEFAULT_STROKE_WIDTH);
                cells[index] = hex;
                this.centerX[index] = centerX;
                this.centerY[index] = centerY;
                boardGroup.getChildren().add(hex);

                var points = hex.getPoints();
                for (int i = 0; i < points.size(); i += 2) {
                    double x = points.get(i);
                    double y = points.get(i + 1);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        double offsetX = -minX;
        double offsetY = -minY;
        boardGroup.getChildren().forEach(node -> {
            if (node instanceof Polygon polygon) {
                var pts = polygon.getPoints();
                for (int i = 0; i < pts.size(); i += 2) {
                    pts.set(i, pts.get(i) + offsetX);
                    pts.set(i + 1, pts.get(i + 1) + offsetY);
                }
            }
        });

        contentWidth = maxX - minX;
        contentHeight = maxY - minY;

        for (int i = 0; i < Board.CELL_COUNT; i++) {
            centerX[i] += offsetX;
            centerY[i] += offsetY;
        }

        lineGroup = new Group();
        lineGroup.setMouseTransparent(true);
        boardGroup.getChildren().add(lineGroup);

        lastMoveMarker = new Circle(HEX_SIZE * 0.4);
        lastMoveMarker.setFill(LAST_MOVE_FILL);
        lastMoveMarker.setStroke(Color.TRANSPARENT);
        lastMoveMarker.setVisible(false);
        lastMoveMarker.setMouseTransparent(true);
        boardGroup.getChildren().add(lastMoveMarker);

        initializeLineOverlays();

        double prefWidth = contentWidth + HEX_SIZE * 4;
        double prefHeight = contentHeight + HEX_SIZE * 4;
        setPrefSize(prefWidth, prefHeight);
        setMinSize(0, 0);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    }

    public void update(GameFrame frame) {
        if (frame == null) {
            for (Polygon cell : cells) {
                if (cell != null) {
                    cell.setFill(EMPTY_FILL);
                    cell.setStroke(GRID_STROKE);
                    cell.setStrokeWidth(DEFAULT_STROKE_WIDTH);
                }
            }
            resetLineState();
            lastMoveMarker.setVisible(false);
            return;
        }

        long firstBits = frame.firstPlayerBits();
        long secondBits = frame.secondPlayerBits();
        long boardBits = frame.state().getBoard().getBits();
        long available = (~boardBits) & FULL_BOARD_MASK;
        int lastMove = frame.lastMove();
        boolean hasLastMove = frame.hasLastMove();

        for (int index = 0; index < cells.length; index++) {
            Polygon cell = cells[index];
            if (cell == null) {
                continue;
            }

            Paint fill;
            if (((firstBits >>> index) & 1L) != 0L) {
                fill = FIRST_PLAYER_FILL;
            } else if (((secondBits >>> index) & 1L) != 0L) {
                fill = SECOND_PLAYER_FILL;
            } else if (((available >>> index) & 1L) != 0L) {
                fill = AVAILABLE_FILL;
            } else {
                fill = EMPTY_FILL;
            }

            cell.setFill(fill);
            cell.setStroke(GRID_STROKE);
            cell.setStrokeWidth(DEFAULT_STROKE_WIDTH);
        }

        updateLineOverlays(frame, firstBits, secondBits, boardBits);
        updateLastMoveMarker(hasLastMove, lastMove);

        currentBoardBits = boardBits;
        currentPly = frame.ply();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        var insets = getInsets();
        double availableWidth = getWidth() - insets.getLeft() - insets.getRight();
        double availableHeight = getHeight() - insets.getTop() - insets.getBottom();
        double offsetX = insets.getLeft() + (availableWidth - contentWidth) / 2.0;
        double offsetY = insets.getTop() + (availableHeight - contentHeight) / 2.0;
        boardGroup.relocate(offsetX, offsetY);
    }

    private static Polygon createHexagon(double centerX, double centerY, double size) {
        Polygon polygon = new Polygon();
        for (int i = 0; i < 6; i++) {
            double angleDeg = 60 * i - 30;
            double angleRad = Math.toRadians(angleDeg);
            double x = centerX + size * Math.cos(angleRad);
            double y = centerY + size * Math.sin(angleRad);
            polygon.getPoints().addAll(x, y);
        }
        return polygon;
    }

    private void initializeLineOverlays() {
        for (int lineIndex = 0; lineIndex < ScoreCalculator.TOTAL_LINES; lineIndex++) {
            long mask = LINE_MASKS[lineIndex];
            int[] cellsInLine = extractCells(mask);
            if (cellsInLine.length == 0) {
                continue;
            }

            Shape shape;
            if (cellsInLine.length == 1) {
                int cellIndex = cellsInLine[0];
                Circle circle = new Circle(centerX[cellIndex], centerY[cellIndex], LINE_THICKNESS / 2.0);
                circle.setVisible(false);
                circle.setMouseTransparent(true);
                circle.setStroke(Color.TRANSPARENT);
                shape = circle;
            } else {
                int[] endpoints = findFarthestCells(cellsInLine);
                Line line = new Line(
                        centerX[endpoints[0]],
                        centerY[endpoints[0]],
                        centerX[endpoints[1]],
                        centerY[endpoints[1]]);
                line.setStrokeWidth(LINE_THICKNESS);
                line.setStroke(Color.TRANSPARENT);
                line.setStrokeLineCap(StrokeLineCap.ROUND);
                line.setVisible(false);
                line.setMouseTransparent(true);
                shape = line;
            }

            lineGroup.getChildren().add(shape);
            lineOverlays[lineIndex] = shape;
        }
    }

    private void updateLineOverlays(GameFrame frame, long firstBits, long secondBits, long boardBits) {
        int newPly = frame.ply();
        if (currentPly == -1 || Math.abs(newPly - currentPly) != 1) {
            resetLineState();
        } else if (newPly < currentPly) {
            List<Integer> removed = linesCompletedByPly.remove(currentPly);
            if (removed != null) {
                for (int lineIndex : removed) {
                    lineOwners[lineIndex] = null;
                }
            }
        } else if (newPly > currentPly) {
            long previousBoard = currentBoardBits;
            boolean lastMoveByFirst = frame.hasLastMove() && !frame.state().getBoard().isFirstPlayer();
            Paint ownerColor = lastMoveByFirst ? FIRST_LINE_COLOR : SECOND_LINE_COLOR;
            List<Integer> completed = null;
            for (int lineIndex = 0; lineIndex < lineOverlays.length; lineIndex++) {
                long mask = LINE_MASKS[lineIndex];
                boolean wasComplete = (previousBoard & mask) == mask;
                boolean isComplete = (boardBits & mask) == mask;
                if (!wasComplete && isComplete) {
                    lineOwners[lineIndex] = ownerColor;
                    if (completed == null) {
                        completed = new ArrayList<>();
                    }
                    completed.add(lineIndex);
                }
            }
            if (completed != null) {
                linesCompletedByPly.put(newPly, completed);
            }
        }

        for (int lineIndex = 0; lineIndex < lineOverlays.length; lineIndex++) {
            Shape overlay = lineOverlays[lineIndex];
            if (overlay == null) {
                continue;
            }

            long mask = LINE_MASKS[lineIndex];
            if ((boardBits & mask) == mask) {
                Paint color = lineOwners[lineIndex];
                if (color == null) {
                    color = determineFallbackOwner(frame, mask, firstBits, secondBits);
                    lineOwners[lineIndex] = color;
                }
                if (color != null) {
                    applyLineColor(overlay, color);
                    overlay.setVisible(true);
                } else {
                    overlay.setVisible(false);
                }
            } else {
                overlay.setVisible(false);
                lineOwners[lineIndex] = null;
            }
        }
    }

    private void hideLineOverlays() {
        for (Shape overlay : lineOverlays) {
            if (overlay != null) {
                overlay.setVisible(false);
            }
        }
    }

    private void resetLineState() {
        hideLineOverlays();
        Arrays.fill(lineOwners, null);
        linesCompletedByPly.clear();
        currentBoardBits = 0L;
        currentPly = -1;
    }

    private void updateLastMoveMarker(boolean hasLastMove, int lastMove) {
        if (!hasLastMove) {
            lastMoveMarker.setVisible(false);
            return;
        }

        lastMoveMarker.setCenterX(centerX[lastMove]);
        lastMoveMarker.setCenterY(centerY[lastMove]);
        lastMoveMarker.setVisible(true);
    }

    private void applyLineColor(Shape overlay, Paint color) {
        if (overlay instanceof Line line) {
            line.setStroke(color);
        } else if (overlay instanceof Circle circle) {
            circle.setFill(color);
        }
        overlay.setVisible(true);
    }

    private Paint determineFallbackOwner(GameFrame frame, long mask, long firstBits, long secondBits) {
        if (frame.hasLastMove() && ((mask >>> frame.lastMove()) & 1L) != 0L) {
            boolean lastMoveByFirst = !frame.state().getBoard().isFirstPlayer();
            return lastMoveByFirst ? FIRST_LINE_COLOR : SECOND_LINE_COLOR;
        }
        int firstCount = Long.bitCount(firstBits & mask);
        int secondCount = Long.bitCount(secondBits & mask);
        if (firstCount == 0 && secondCount == 0) {
            return null;
        }
        return firstCount >= secondCount ? FIRST_LINE_COLOR : SECOND_LINE_COLOR;
    }

    private static int[] extractCells(long mask) {
        int[] cells = new int[Long.bitCount(mask)];
        int idx = 0;
        long remaining = mask;
        while (remaining != 0L) {
            int cell = Long.numberOfTrailingZeros(remaining);
            cells[idx++] = cell;
            remaining &= remaining - 1;
        }
        return cells;
    }

    private int[] findFarthestCells(int[] cells) {
        double maxDistance = -1.0;
        int start = cells[0];
        int end = cells[0];
        for (int i = 0; i < cells.length; i++) {
            for (int j = i + 1; j < cells.length; j++) {
                int cellA = cells[i];
                int cellB = cells[j];
                double dx = centerX[cellA] - centerX[cellB];
                double dy = centerY[cellA] - centerY[cellB];
                double distance = dx * dx + dy * dy;
                if (distance > maxDistance) {
                    maxDistance = distance;
                    start = cellA;
                    end = cellB;
                }
            }
        }
        return new int[] {start, end};
    }

    private static int index(int row, int col) {
        return row * (row + 1) / 2 + col;
    }
}
