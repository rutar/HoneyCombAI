package com.honeycomb.visualizer.ui;

import com.honeycomb.core.Board;
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
    private static final Paint FIRST_AVAILABLE_FILL = Color.web("#4F6BED", 0.35);
    private static final Paint SECOND_AVAILABLE_FILL = Color.web("#E57373", 0.35);
    private static final Paint LAST_MOVE_STROKE = Color.web("#FFB300");
    private static final double DEFAULT_STROKE_WIDTH = 1.0;
    private static final double HIGHLIGHT_STROKE_WIDTH = 3.0;
    private static final long FULL_BOARD_MASK = -1L >>> (64 - Board.CELL_COUNT);

    private final Polygon[] cells = new Polygon[Board.CELL_COUNT];

    public BoardView() {
        setPadding(new Insets(16));
        setStyle("-fx-background-color: linear-gradient(to bottom, #fdfdfd, #e7ebf5);");
        Group group = new Group();
        getChildren().add(group);

        double rootX = HORIZONTAL_SPACING * (BOARD_HEIGHT - 1) / 2.0;

        for (int row = 0; row < BOARD_HEIGHT; row++) {
            for (int col = 0; col <= row; col++) {
                int index = index(row, col);
                double centerX = rootX + (col - row / 2.0) * HORIZONTAL_SPACING;
                double centerY = row * VERTICAL_SPACING;
                Polygon hex = createHexagon(centerX, centerY, HEX_SIZE);
                hex.setFill(EMPTY_FILL);
                hex.setStroke(GRID_STROKE);
                hex.setStrokeWidth(DEFAULT_STROKE_WIDTH);
                cells[index] = hex;
                group.getChildren().add(hex);
            }
        }

        double prefWidth = HORIZONTAL_SPACING * BOARD_HEIGHT + HEX_SIZE * 4;
        double prefHeight = VERTICAL_SPACING * BOARD_HEIGHT + HEX_SIZE * 4;
        setPrefSize(prefWidth, prefHeight);
        setMinSize(Pane.USE_PREF_SIZE, Pane.USE_PREF_SIZE);
        setMaxSize(Pane.USE_COMPUTED_SIZE, Pane.USE_COMPUTED_SIZE);
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
            } else if (((available >>> index) & 1L) != 0L) {
                fill = firstPlayerTurn ? FIRST_AVAILABLE_FILL : SECOND_AVAILABLE_FILL;
            } else {
                fill = EMPTY_FILL;
            }
            cell.setFill(fill);

            if (index == lastMove && frame.hasLastMove()) {
                cell.setStroke(LAST_MOVE_STROKE);
                cell.setStrokeWidth(HIGHLIGHT_STROKE_WIDTH);
            } else {
                cell.setStroke(GRID_STROKE);
                cell.setStrokeWidth(DEFAULT_STROKE_WIDTH);
            }
        }
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
