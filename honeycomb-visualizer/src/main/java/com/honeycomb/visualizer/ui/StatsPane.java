package com.honeycomb.visualizer.ui;

import com.honeycomb.visualizer.model.GameFrame;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Displays aggregated information about the current simulation frame.
 */
public final class StatsPane extends VBox {

    private final Label moveValue = valueLabel();
    private final Label turnValue = valueLabel();
    private final Label lastMoveValue = valueLabel();
    private final Label scoreValue = valueLabel();
    private final Label nodesValue = valueLabel();
    private final Label timeoutValue = valueLabel();
    private final Label ttSizeValue = valueLabel();
    private final Label ttDepthValue = valueLabel();
    private final Label ttFlagValue = valueLabel();
    private final Label ttChangeValue = valueLabel();
    private final Label ttPreviousDepthValue = valueLabel();
    private final Label ttValueValue = valueLabel();

    public StatsPane() {
        setPadding(new Insets(16));
        setSpacing(12);
        setStyle("-fx-background-color: rgba(255,255,255,0.85); -fx-border-color: #d0d6e6; -fx-border-radius: 6; -fx-background-radius: 6;");
        setPrefWidth(280);
        setMinWidth(280);
        setMaxWidth(280);

        Label title = new Label("Статистика");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        addRow(grid, 0, "Ход", moveValue);
        addRow(grid, 1, "Ходит", turnValue);
        addRow(grid, 2, "Последний ход", lastMoveValue);
        addRow(grid, 3, "Счет", scoreValue);
        addRow(grid, 4, "Посещено узлов", nodesValue);
        addRow(grid, 5, "Таймаут", timeoutValue);
        addRow(grid, 6, "TT записи", ttSizeValue);
        addRow(grid, 7, "TT глубина", ttDepthValue);
        addRow(grid, 8, "TT предыдущая глубина", ttPreviousDepthValue);
        addRow(grid, 9, "TT оценка", ttValueValue);
        addRow(grid, 10, "TT флаг", ttFlagValue);
        addRow(grid, 11, "TT изменение", ttChangeValue);

        getChildren().addAll(title, grid);
    }

    public void update(GameFrame frame) {
        if (frame == null) {
            moveValue.setText("—");
            turnValue.setText("—");
            lastMoveValue.setText("—");
            scoreValue.setText("—");
            nodesValue.setText("—");
            timeoutValue.setText("—");
            ttSizeValue.setText("—");
            ttDepthValue.setText("—");
            ttFlagValue.setText("—");
            ttChangeValue.setText("—");
            ttPreviousDepthValue.setText("—");
            ttValueValue.setText("—");
            return;
        }

        moveValue.setText(String.valueOf(frame.ply()));
        turnValue.setText(frame.state().getBoard().isFirstPlayer() ? "Первый" : "Второй");
        if (frame.hasLastMove()) {
            boolean lastByFirst = !frame.state().getBoard().isFirstPlayer();
            lastMoveValue.setText(String.format("%d (%s)", frame.lastMove(), lastByFirst ? "первый" : "второй"));
        } else {
            lastMoveValue.setText("—");
        }
        scoreValue.setText(String.format("%d : %d", frame.state().getScore(true), frame.state().getScore(false)));
        nodesValue.setText(frame.visitedNodes() > 0 ? Long.toString(frame.visitedNodes()) : "—");
        timeoutValue.setText(frame.timedOut() ? "Да" : "Нет");
        ttSizeValue.setText(Integer.toString(frame.tableSize()));

        var event = frame.tableEvent();
        if (event == null) {
            ttDepthValue.setText("—");
            ttFlagValue.setText("—");
            ttChangeValue.setText("—");
            ttPreviousDepthValue.setText("—");
            ttValueValue.setText("—");
        } else {
            ttDepthValue.setText(Integer.toString(event.entry().depth()));
            ttFlagValue.setText(event.entry().flag().name());
            ttChangeValue.setText(String.format("%016X (%s)", event.key(), event.replaced() ? "обновлен" : "без изм."));
            ttPreviousDepthValue.setText(event.previousEntry() == null ? "—" : Integer.toString(event.previousEntry().depth()));
            ttValueValue.setText(Integer.toString(event.entry().value()));
        }
    }

    private static void addRow(GridPane grid, int row, String label, Label value) {
        Label caption = new Label(label + ":");
        caption.setStyle("-fx-text-fill: #4a4f64; -fx-font-weight: 600;");
        value.setStyle("-fx-text-fill: #1f2333;");
        grid.addRow(row, caption, value);
    }

    private static Label valueLabel() {
        Label label = new Label("—");
        label.setStyle("-fx-font-size: 14px;");
        return label;
    }
}
