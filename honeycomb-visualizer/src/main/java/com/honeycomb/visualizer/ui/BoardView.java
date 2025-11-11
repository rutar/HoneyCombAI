package com.honeycomb.visualizer.ui;

import com.honeycomb.core.Board;
import com.honeycomb.core.ScoreCalculator;
import com.honeycomb.visualizer.model.GameFrame;
import java.util.Arrays;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Polygon;

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
    private static final Paint FIRST_AVAILABLE_STROKE = Color.web("#4F6BED", 0.9);
    private static final Paint SECOND_AVAILABLE_STROKE = Color.web("#E57373", 0.9);
    private static final Paint FIRST_LINE_STROKE = Color.web("#243A9A");
    private static final Paint SECOND_LINE_STROKE = Color.web("#B71C1C");
    private static final Paint LAST_MOVE_STROKE = Color.web("#FFB300");
    private static final double DEFAULT_STROKE_WIDTH = 1.0;
    private static final double AVAILABLE_STROKE_WIDTH = 1.8;
    private static final double LINE_STROKE_WIDTH = 2.4;
    private static final double LAST_MOVE_STROKE_WIDTH = 3.2;
    private static final long FULL_BOARD_MASK = -1L >>> (64 - Board.CELL_COUNT);

    private final Polygon[] cells = new Polygon[Board.CELL_COUNT];
    private final int[][] cellLines = new int[Board.CELL_COUNT][];
    private final Group boardGroup;
    private double contentWidth;
    private double contentHeight;

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
                cellLines[index] = ScoreCalculator.getLinesForCell(index);
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

        double prefWidth = contentWidth + HEX_SIZE * 4;
        double prefHeight = contentHeight + HEX_SIZE * 4;
        setPrefSize(prefWidth, prefHeight);
        setMinSize(0, 0);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    }

    public void update(GameFrame frame) {
        if (frame == null) {
            Arrays.stream(cells).filter(cell -> cell != null).forEach(cell -> {
                cell.setFill(EMPTY_FILL);
                cell.setStroke(GRID_STROKE);
                cell.setStrokeWidth(DEFAULT_STROKE_WIDTH);
            });
            return;
        }

        long firstBits = frame.firstPlayerBits();
        long secondBits = frame.secondPlayerBits();
        long firstLines = frame.firstPlayerLines();
        long secondLines = frame.secondPlayerLines();
        long boardBits = frame.state().getBoard().getBits();
        long available = (~boardBits) & FULL_BOARD_MASK;
        boolean firstPlayerTurn = frame.state().getBoard().isFirstPlayer();
        int lastMove = frame.lastMove();

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
            } else {
                fill = EMPTY_FILL;
            }
            cell.setFill(fill);

            Paint stroke = GRID_STROKE;
            double strokeWidth = DEFAULT_STROKE_WIDTH;

            boolean highlighted = false;
            if (cellLines[index] != null) {
                for (int lineIndex : cellLines[index]) {
                    if (((firstLines >>> lineIndex) & 1L) != 0L) {
                        stroke = FIRST_LINE_STROKE;
                        strokeWidth = LINE_STROKE_WIDTH;
                        highlighted = true;
                        break;
                    }
                    if (((secondLines >>> lineIndex) & 1L) != 0L) {
                        stroke = SECOND_LINE_STROKE;
                        strokeWidth = LINE_STROKE_WIDTH;
                        highlighted = true;
                        break;
                    }
                }
            }

            if (!highlighted && ((available >>> index) & 1L) != 0L && ((firstBits >>> index) & 1L) == 0L
                    && ((secondBits >>> index) & 1L) == 0L) {
                stroke = firstPlayerTurn ? FIRST_AVAILABLE_STROKE : SECOND_AVAILABLE_STROKE;
                strokeWidth = AVAILABLE_STROKE_WIDTH;
            }

            if (index == lastMove && frame.hasLastMove()) {
                stroke = LAST_MOVE_STROKE;
                strokeWidth = LAST_MOVE_STROKE_WIDTH;
            }

            cell.setStroke(stroke);
            cell.setStrokeWidth(strokeWidth);
        }
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

    private static int index(int row, int col) {
        return row * (row + 1) / 2 + col;
    }
}
