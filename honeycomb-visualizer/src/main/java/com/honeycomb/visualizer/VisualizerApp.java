package com.honeycomb.visualizer;

import com.honeycomb.core.GameState;
import com.honeycomb.core.ai.NegamaxAI;
import com.honeycomb.core.ai.TranspositionTable;
import com.honeycomb.visualizer.model.GameFrame;
import com.honeycomb.visualizer.simulation.TrainingSimulationTask;
import com.honeycomb.visualizer.ui.BoardView;
import com.honeycomb.visualizer.ui.StatsPane;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

public final class VisualizerApp extends Application {

    private static final int MAX_DEPTH = 6;
    private static final int DEFAULT_DEPTH = 3;
    private static final Duration TIME_LIMIT = Duration.ofMillis(200);
    private static final Logger LOGGER = Logger.getLogger(VisualizerApp.class.getName());

    private final ObservableList<GameFrame> frames = FXCollections.observableArrayList();
    private final IntegerProperty currentIndex = new SimpleIntegerProperty(0);
    private final ObjectProperty<GameFrame> currentFrame = new SimpleObjectProperty<>();
    private final BooleanProperty playing = new SimpleBooleanProperty(false);
    private final BooleanProperty simulationRunning = new SimpleBooleanProperty(false);
    private final ObjectProperty<TranspositionTable.PersistenceStatus> tableStatus =
            new SimpleObjectProperty<>(TranspositionTable.PersistenceStatus.NOT_LOADED);

    private Timeline playbackTimeline;
    private NegamaxAI ai;
    private TranspositionTable transpositionTable;
    private BoardView boardView;
    private StatsPane statsPane;
    private Spinner<Integer> depthSpinner;
    private ProgressBar progressBar;
    private Label statusLabel;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.transpositionTable = new TranspositionTable();
        this.ai = new NegamaxAI(MAX_DEPTH, TIME_LIMIT, transpositionTable);
        tableStatus.set(transpositionTable.getPersistenceStatus());
        transpositionTable.addPersistenceListener(status -> Platform.runLater(() -> tableStatus.set(status)));
        loadTranspositionTableAsync();

        boardView = new BoardView();
        statsPane = new StatsPane();
        statsPane.bindTableStatus(tableStatus);

        setupIndexListener();
        setupPlaybackTimeline();

        currentFrame.addListener((obs, oldFrame, newFrame) -> {
            boardView.update(newFrame);
            statsPane.update(newFrame);
        });

        GameFrame initialFrame = GameFrame.initial(new GameState(), transpositionTable);
        frames.setAll(initialFrame);
        currentFrame.set(initialFrame);
        currentIndex.set(0);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setCenter(boardView);
        BorderPane.setAlignment(boardView, Pos.CENTER);
        root.setRight(statsPane);
        BorderPane.setMargin(statsPane, new Insets(0, 0, 0, 16));

        HBox controls = buildControls();
        root.setBottom(controls);
        BorderPane.setMargin(controls, new Insets(16, 0, 0, 0));

        Scene scene = new Scene(root, 1200, 800);
        stage.setTitle("Honeycomb Visualizer");
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(720);
        stage.show();
    }

    private void loadTranspositionTableAsync() {
        transpositionTable.loadFromDiskAsync().exceptionally(ex -> {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            LOGGER.log(Level.WARNING, "Failed to load persisted transposition table", cause);
            return null;
        });
    }

    private void setupIndexListener() {
        currentIndex.addListener((obs, oldValue, newValue) -> {
            if (frames.isEmpty()) {
                currentFrame.set(null);
                return;
            }
            int requested = newValue.intValue();
            int clamped = Math.max(0, Math.min(requested, frames.size() - 1));
            if (clamped != requested) {
                currentIndex.set(clamped);
                return;
            }
            currentFrame.set(frames.get(clamped));
        });
    }

    private void setupPlaybackTimeline() {
        playbackTimeline = new Timeline(new KeyFrame(javafx.util.Duration.millis(800), event -> advanceFrame()));
        playbackTimeline.setCycleCount(Timeline.INDEFINITE);
    }

    private HBox buildControls() {
        Button simulateButton = new Button("Сыграть партию");
        simulateButton.setOnAction(event -> runSimulation(depthSpinner.getValue()));

        Button previousButton = new Button("⏮");
        previousButton.setOnAction(event -> {
            pausePlayback();
            stepBackward();
        });

        Button nextButton = new Button("⏭");
        nextButton.setOnAction(event -> {
            pausePlayback();
            stepForward();
        });

        Button playButton = new Button("▶");
        playButton.setOnAction(event -> startPlayback());

        Button pauseButton = new Button("⏸");
        pauseButton.setOnAction(event -> pausePlayback());

        Button resetButton = new Button("⏮⏮");
        resetButton.setOnAction(event -> {
            pausePlayback();
            if (!frames.isEmpty()) {
                currentIndex.set(0);
            }
        });

        depthSpinner = new Spinner<>();
        depthSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, MAX_DEPTH, DEFAULT_DEPTH));
        depthSpinner.setPrefWidth(80);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(180);

        statusLabel = new Label("Готово");
        statusLabel.setMinWidth(160);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox navigation = new HBox(8, resetButton, previousButton, nextButton, playButton, pauseButton);
        navigation.setAlignment(Pos.CENTER_LEFT);

        HBox controls = new HBox(12,
                simulateButton,
                new Label("Глубина:"),
                depthSpinner,
                navigation,
                spacer,
                progressBar,
                statusLabel);
        controls.setAlignment(Pos.CENTER_LEFT);

        var frameCount = Bindings.size(frames);
        previousButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> currentIndex.get() <= 0, currentIndex, frameCount));
        nextButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> frames.isEmpty() || currentIndex.get() >= frames.size() - 1, currentIndex, frameCount));
        resetButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> frames.isEmpty() || currentIndex.get() == 0, currentIndex, frameCount));
        playButton.disableProperty().bind(playing.or(frameCount.lessThanOrEqualTo(1)).or(simulationRunning));
        pauseButton.disableProperty().bind(playing.not());
        simulateButton.disableProperty().bind(simulationRunning);
        depthSpinner.disableProperty().bind(simulationRunning);

        return controls;
    }

    private void startPlayback() {
        if (frames.size() <= 1) {
            return;
        }
        playing.set(true);
        playbackTimeline.play();
    }

    private void pausePlayback() {
        playbackTimeline.stop();
        playing.set(false);
    }

    private void stepForward() {
        if (frames.isEmpty()) {
            return;
        }
        int nextIndex = Math.min(frames.size() - 1, currentIndex.get() + 1);
        currentIndex.set(nextIndex);
    }

    private void stepBackward() {
        if (frames.isEmpty()) {
            return;
        }
        int prevIndex = Math.max(0, currentIndex.get() - 1);
        currentIndex.set(prevIndex);
    }

    private void advanceFrame() {
        if (frames.isEmpty()) {
            pausePlayback();
            return;
        }
        int next = currentIndex.get() + 1;
        if (next >= frames.size()) {
            pausePlayback();
            return;
        }
        currentIndex.set(next);
    }

    private void runSimulation(int depthLimit) {
        pausePlayback();

        GameFrame initialFrame = GameFrame.initial(new GameState(), transpositionTable);
        frames.setAll(initialFrame);
        currentIndex.set(0);
        currentFrame.set(initialFrame);

        TrainingSimulationTask task = new TrainingSimulationTask(ai, transpositionTable, depthLimit, frame -> {
            frames.add(frame);
            if (simulationRunning.get()) {
                currentIndex.set(frames.size() - 1);
            }
        });
        simulationRunning.set(true);
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(event -> {
            cleanupTaskBindings();
            List<GameFrame> result = task.getValue();
            frames.setAll(result);
            if (!frames.isEmpty()) {
                currentIndex.set(0);
                currentFrame.set(frames.get(0));
            } else {
                currentIndex.set(0);
                currentFrame.set(null);
            }
            progressBar.setProgress(1.0);
            statusLabel.setText("Готово");
        });

        task.setOnFailed(event -> {
            cleanupTaskBindings();
            Throwable error = task.getException();
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            statusLabel.setText(error == null ? "Ошибка" : "Ошибка: " + error.getMessage());
            if (error != null) {
                error.printStackTrace();
            }
        });

        task.setOnCancelled(event -> {
            cleanupTaskBindings();
            progressBar.setProgress(0);
            statusLabel.setText("Отменено");
        });

        Thread thread = new Thread(task, "honeycomb-visualizer-simulation");
        thread.setDaemon(true);
        thread.start();
    }

    private void cleanupTaskBindings() {
        simulationRunning.set(false);
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
    }
}
