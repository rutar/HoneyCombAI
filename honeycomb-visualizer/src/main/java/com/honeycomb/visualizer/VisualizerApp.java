package com.honeycomb.visualizer;

import com.honeycomb.core.GameState;
import com.honeycomb.core.ai.NegamaxAI;
import com.honeycomb.core.ai.SearchConstraints;
import com.honeycomb.core.ai.SearchResult;
import com.honeycomb.core.ai.SearchTelemetry;
import com.honeycomb.core.ai.TranspositionTable;
import com.honeycomb.core.Board;
import com.honeycomb.visualizer.model.GameFrame;
import com.honeycomb.visualizer.simulation.TrainingSimulationTask;
import com.honeycomb.visualizer.ui.BoardView;
import com.honeycomb.visualizer.ui.StatsPane;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
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
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.StringConverter;

public final class VisualizerApp extends Application {

    private static final int MAX_DEPTH = 9;
    private static final int DEFAULT_DEPTH = 3;
    private static final Duration DEFAULT_TIME_LIMIT = Duration.ofMillis(2000);
    private static final Duration DEFAULT_MIN_THINK_TIME = Duration.ofMillis(75);
    private static final Logger LOGGER = Logger.getLogger(VisualizerApp.class.getName());
    private static final long FULL_BOARD_MASK = -1L >>> (64 - Board.CELL_COUNT);

    private enum ControllerType {
        HUMAN("Human"),
        AI("AI");

        private final String label;

        ControllerType(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final ObservableList<GameFrame> frames = FXCollections.observableArrayList();
    private final IntegerProperty currentIndex = new SimpleIntegerProperty(0);
    private final ObjectProperty<GameFrame> currentFrame = new SimpleObjectProperty<>();
    private final BooleanProperty playing = new SimpleBooleanProperty(false);
    private final BooleanProperty simulationRunning = new SimpleBooleanProperty(false);
    private final BooleanProperty matchRunning = new SimpleBooleanProperty(false);
    private final BooleanProperty aiTurnInProgress = new SimpleBooleanProperty(false);
    private final ObjectProperty<TranspositionTable.PersistenceStatus> tableStatus =
            new SimpleObjectProperty<>(TranspositionTable.PersistenceStatus.NOT_LOADED);

    private Timeline playbackTimeline;
    private NegamaxAI ai;
    private SearchConstraints.SearchMode searchMode = SearchConstraints.SearchMode.SEQ;
    private TranspositionTable transpositionTable;
    private BoardView boardView;
    private StatsPane statsPane;
    private Spinner<Integer> depthSpinner;
    private ComboBox<SearchConstraints.SearchMode> searchModeComboBox;
    private ComboBox<ControllerType> firstControllerComboBox;
    private ComboBox<ControllerType> secondControllerComboBox;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Spinner<Integer> minThinkTimeSpinner;
    private Spinner<Integer> timeLimitSpinner;
    private Button startGameButton;
    private Button stopGameButton;
    private GameState activeGameState;
    private long activeFirstBits;
    private long activeSecondBits;
    private int matchDepthLimit = DEFAULT_DEPTH;
    private Duration matchTimeLimit = DEFAULT_TIME_LIMIT;
    private Duration matchMinThinkTime = DEFAULT_MIN_THINK_TIME;
    private Task<SearchResult> aiMoveTask;
    private int expectedAiPly;
    private CompletableFuture<List<GameFrame>> simulationFuture;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        configureSearchMode(getParameters().getRaw());
        this.transpositionTable = new TranspositionTable();

        transpositionTable.addPersistenceListener(status -> Platform.runLater(() -> tableStatus.set(status)));
        loadTranspositionTableAsync();

        this.ai = new NegamaxAI(MAX_DEPTH, DEFAULT_TIME_LIMIT, DEFAULT_MIN_THINK_TIME, transpositionTable);
        this.ai.setMode(searchMode);

        boardView = new BoardView();
        boardView.setInteractive(false);
        boardView.setOnCellClicked(this::handleHumanMove);
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
        transpositionTable.loadFromDiskAsync().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                LOGGER.log(Level.WARNING, "Failed to load persisted transposition table", cause);
                return;
            }
            Platform.runLater(this::refreshCurrentFrameWithTableSize);
        });
    }

    private void refreshCurrentFrameWithTableSize() {
        if (frames.isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(currentIndex.get(), frames.size() - 1));
        GameFrame frame = frames.get(index);
        GameFrame updated = new GameFrame(
                frame.state(),
                frame.lastMove(),
                frame.firstPlayerBits(),
                frame.secondPlayerBits(),
                frame.visitedNodes(),
                frame.timedOut(),
                frame.telemetry(),
                transpositionTable.getLastUpdate(),
                transpositionTable.size(),
                frame.ply());
        frames.set(index, updated);
        currentFrame.set(updated);
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
        playbackTimeline = new Timeline(new KeyFrame(
                javafx.util.Duration.millis(DEFAULT_MIN_THINK_TIME.toMillis()),
                event -> advanceFrame()));
        playbackTimeline.setCycleCount(Timeline.INDEFINITE);
    }


    private HBox buildControls() {
        Button simulateButton = new Button("Run simulation");
        simulateButton.setOnAction(event -> runSimulation(depthSpinner.getValue()));

        startGameButton = new Button("Start game");
        startGameButton.setOnAction(event -> startMatch());

        stopGameButton = new Button("Stop game");
        stopGameButton.setOnAction(event -> stopMatch());

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

        searchModeComboBox = new ComboBox<>();
        searchModeComboBox.getItems().setAll(SearchConstraints.SearchMode.values());
        searchModeComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(SearchConstraints.SearchMode mode) {
                if (mode == null) {
                    return "";
                }
                return switch (mode) {
                    case SEQ -> "Sequential";
                    case PAR -> "Parallel";
                };
            }

            @Override
            public SearchConstraints.SearchMode fromString(String string) {
                if (string == null) {
                    return null;
                }
                return switch (string.toLowerCase()) {
                    case "sequential" -> SearchConstraints.SearchMode.SEQ;
                    case "parallel" -> SearchConstraints.SearchMode.PAR;
                    default -> null;
                };
            }
        });
        searchModeComboBox.setValue(searchMode);
        searchModeComboBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                searchMode = newValue;
                if (ai != null) {
                    ai.setMode(newValue);
                }
            }
        });

        timeLimitSpinner = new Spinner<>();
        timeLimitSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 60000,
                (int) DEFAULT_TIME_LIMIT.toMillis(), 100));
        timeLimitSpinner.setEditable(true);
        timeLimitSpinner.setPrefWidth(120);

        minThinkTimeSpinner = new Spinner<>();
        minThinkTimeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 5000,
                (int) DEFAULT_MIN_THINK_TIME.toMillis(), 10));
        minThinkTimeSpinner.setEditable(true);
        minThinkTimeSpinner.setPrefWidth(100);

        firstControllerComboBox = new ComboBox<>();
        firstControllerComboBox.getItems().setAll(ControllerType.values());
        firstControllerComboBox.setValue(ControllerType.HUMAN);

        secondControllerComboBox = new ComboBox<>();
        secondControllerComboBox.getItems().setAll(ControllerType.values());
        secondControllerComboBox.setValue(ControllerType.AI);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(180);

        statusLabel = new Label("Ready");
        statusLabel.setMinWidth(160);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox navigation = new HBox(8, resetButton, previousButton, nextButton, playButton, pauseButton);
        navigation.setAlignment(Pos.CENTER_LEFT);

        var tableReady = Bindings.createBooleanBinding(
                () -> tableStatus.get() == TranspositionTable.PersistenceStatus.READY, tableStatus);

        HBox controls = new HBox(12,
                simulateButton,
                startGameButton,
                stopGameButton,
                new Label("Depth:"),
                depthSpinner,
                new Label("Search mode:"),
                searchModeComboBox,
                new Label("Time limit (ms):"),
                timeLimitSpinner,
                new Label("Min. think time (ms):"),
                minThinkTimeSpinner,
                new Label("First:"),
                firstControllerComboBox,
                new Label("Second:"),
                secondControllerComboBox,
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
        playButton.disableProperty().bind(playing.or(frameCount.lessThanOrEqualTo(1)).or(simulationRunning)
                .or(matchRunning).or(aiTurnInProgress));
        pauseButton.disableProperty().bind(playing.not());
        simulateButton.disableProperty().bind(simulationRunning.or(tableReady.not()).or(matchRunning));
        startGameButton.disableProperty().bind(simulationRunning.or(tableReady.not()).or(matchRunning));
        stopGameButton.disableProperty().bind(matchRunning.not());
        depthSpinner.disableProperty().bind(simulationRunning.or(matchRunning));
        timeLimitSpinner.disableProperty().bind(simulationRunning.or(matchRunning));
        minThinkTimeSpinner.disableProperty().bind(simulationRunning.or(matchRunning));
        searchModeComboBox.disableProperty().bind(simulationRunning.or(matchRunning));

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
        if (tableStatus.get() != TranspositionTable.PersistenceStatus.READY) {
            return;
        }
        pausePlayback();
        stopMatch();

        Duration timeLimit = Duration.ofMillis(Math.max(0, normalizeSpinnerValue(timeLimitSpinner)));
        Duration minThinkTime = Duration.ofMillis(Math.max(0, normalizeSpinnerValue(minThinkTimeSpinner)));
        this.ai = new NegamaxAI(MAX_DEPTH, timeLimit, minThinkTime, transpositionTable);
        this.ai.setMode(searchMode);

        GameFrame initialFrame = GameFrame.initial(new GameState(), transpositionTable);
        frames.setAll(initialFrame);
        currentIndex.set(0);
        currentFrame.set(initialFrame);

        TrainingSimulationTask task = new TrainingSimulationTask(ai, transpositionTable, depthLimit, timeLimit,
                searchMode, frame -> {
            frames.add(frame);
            if (simulationRunning.get()) {
                currentIndex.set(frames.size() - 1);
            }
        });
        simulationRunning.set(true);
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());

        simulationFuture = attachSimulationHandlers(task);
        simulationFuture.whenComplete((result, error) -> {
            if (error != null && !(error instanceof InterruptedException)
                    && !(error instanceof CancellationException)) {
                LOGGER.log(Level.FINE, "Simulation completed with error", error);
            }
        });

        Thread thread = new Thread(task, "honeycomb-visualizer-simulation");
        thread.setDaemon(true);
        thread.start();
    }

    private void startMatch() {
        if (tableStatus.get() != TranspositionTable.PersistenceStatus.READY) {
            return;
        }
        pausePlayback();
        stopMatch();

        matchDepthLimit = Math.max(1, normalizeSpinnerValue(depthSpinner));
        matchTimeLimit = Duration.ofMillis(Math.max(0, normalizeSpinnerValue(timeLimitSpinner)));
        matchMinThinkTime = Duration.ofMillis(Math.max(0, normalizeSpinnerValue(minThinkTimeSpinner)));

        this.ai = new NegamaxAI(MAX_DEPTH, matchTimeLimit, matchMinThinkTime, transpositionTable);
        this.ai.setMode(searchMode);

        activeGameState = new GameState();
        activeFirstBits = 0L;
        activeSecondBits = 0L;

        GameFrame initialFrame = GameFrame.initial(activeGameState, transpositionTable);
        frames.setAll(initialFrame);
        currentIndex.set(0);
        currentFrame.set(initialFrame);

        matchRunning.set(true);
        aiTurnInProgress.set(false);
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        progressBar.setProgress(0);
        statusLabel.setText("Game started");
        proceedWithCurrentTurn();
    }

    private void stopMatch() {
        matchRunning.set(false);
        aiTurnInProgress.set(false);
        if (aiMoveTask != null) {
            aiMoveTask.cancel(true);
            aiMoveTask = null;
        }
        boardView.setInteractive(false);
        boardView.setAvailableCellsMask(0L);
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        progressBar.setProgress(0);
        statusLabel.setText("Ready");
    }

    private void proceedWithCurrentTurn() {
        boardView.setAvailableCellsMask(0L);
        if (!matchRunning.get() || activeGameState == null) {
            boardView.setInteractive(false);
            return;
        }
        if (activeGameState.isGameOver()) {
            finishMatch("Game over");
            return;
        }
        if (isAiTurn()) {
            runAiTurn();
        } else {
            aiTurnInProgress.set(false);
            progressBar.setProgress(0);
            statusLabel.setText("Your move");
            boardView.setAvailableCellsMask(computeAvailableCellsMask(activeGameState));
            boardView.setInteractive(true);
        }
    }

    private void finishMatch(String message) {
        matchRunning.set(false);
        aiTurnInProgress.set(false);
        boardView.setInteractive(false);
        boardView.setAvailableCellsMask(0L);
        progressBar.setProgress(1.0);
        statusLabel.setText(message);
    }

    private boolean isAiTurn() {
        boolean firstToMove = activeGameState.getBoard().isFirstPlayer();
        ControllerType controller = firstToMove ? firstControllerComboBox.getValue() : secondControllerComboBox.getValue();
        return controller == ControllerType.AI;
    }

    private boolean isHumanTurn() {
        return matchRunning.get() && !isAiTurn();
    }

    private long computeAvailableCellsMask(GameState state) {
        if (state == null) {
            return 0L;
        }
        long boardBits = state.getBoard().getBits();
        return (~boardBits) & FULL_BOARD_MASK;
    }

    private void handleHumanMove(int cellIndex) {
        if (!matchRunning.get() || aiTurnInProgress.get() || activeGameState == null) {
            return;
        }
        if (!isHumanTurn()) {
            return;
        }
        if (!activeGameState.getBoard().isEmpty(cellIndex)) {
            return;
        }
        long availableMask = computeAvailableCellsMask(activeGameState);
        if (((availableMask >>> cellIndex) & 1L) == 0L) {
            return;
        }
        applyMove(cellIndex, 0L, false, SearchTelemetry.empty());
    }

    private void runAiTurn() {
        if (!matchRunning.get() || activeGameState == null) {
            return;
        }
        aiTurnInProgress.set(true);
        boardView.setInteractive(false);
        boardView.setAvailableCellsMask(0L);
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();

        expectedAiPly = activeGameState.getMoveNumber();
        SearchConstraints constraints = new SearchConstraints(matchDepthLimit, matchTimeLimit, searchMode);

        aiMoveTask = new Task<>() {
            @Override
            protected SearchResult call() {
                updateMessage("AI thinking...");
                updateProgress(-1, 1);
                return ai.search(activeGameState, constraints);
            }
        };

        progressBar.progressProperty().bind(aiMoveTask.progressProperty());
        statusLabel.textProperty().bind(aiMoveTask.messageProperty());

        aiMoveTask.setOnSucceeded(event -> {
            cleanupAiTaskBindings();
            SearchResult result = aiMoveTask.getValue();
            Platform.runLater(() -> handleAiResult(expectedAiPly, result));
        });

        aiMoveTask.setOnFailed(event -> {
            cleanupAiTaskBindings();
            Platform.runLater(() -> handleAiFailure(aiMoveTask.getException()));
        });

        aiMoveTask.setOnCancelled(event -> {
            cleanupAiTaskBindings();
            aiTurnInProgress.set(false);
            Platform.runLater(() -> statusLabel.setText("AI cancelled"));
        });

        Thread thread = new Thread(aiMoveTask, "honeycomb-visualizer-ai-turn");
        thread.setDaemon(true);
        thread.start();
    }

    private void cleanupAiTaskBindings() {
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        progressBar.setProgress(0);
        aiMoveTask = null;
    }

    private void handleAiResult(int expectedPly, SearchResult result) {
        aiTurnInProgress.set(false);
        progressBar.setProgress(0);
        if (!matchRunning.get() || activeGameState == null) {
            return;
        }
        if (expectedPly != activeGameState.getMoveNumber()) {
            return;
        }
        applyMove(result.move(), result.visitedNodes(), result.timedOut(), result.telemetry());
    }

    private void handleAiFailure(Throwable error) {
        aiTurnInProgress.set(false);
        progressBar.setProgress(0);
        statusLabel.setText("AI error");
        if (error != null) {
            LOGGER.log(Level.SEVERE, "AI move failed", error);
        }
        stopMatch();
        statusLabel.setText("AI error");
    }

    private void applyMove(int move, long visitedNodes, boolean timedOut, SearchTelemetry telemetry) {
        if (activeGameState == null) {
            return;
        }
        boolean firstToMove = activeGameState.getBoard().isFirstPlayer();
        if (firstToMove) {
            activeFirstBits |= (1L << move);
        } else {
            activeSecondBits |= (1L << move);
        }

        activeGameState = activeGameState.applyMove(move);
        GameFrame frame = new GameFrame(
                activeGameState,
                move,
                activeFirstBits,
                activeSecondBits,
                visitedNodes,
                timedOut,
                telemetry,
                transpositionTable.getLastUpdate(),
                transpositionTable.size(),
                activeGameState.getMoveNumber());
        frames.add(frame);
        currentIndex.set(frames.size() - 1);
        currentFrame.set(frame);
        proceedWithCurrentTurn();
    }

    private int normalizeSpinnerValue(Spinner<Integer> spinner) {
        SpinnerValueFactory<Integer> factory = spinner.getValueFactory();
        if (factory != null) {
            try {
                Integer parsed = factory.getConverter().fromString(spinner.getEditor().getText());
                if (parsed != null) {
                    factory.setValue(parsed);
                }
            } catch (NumberFormatException ignored) {
                // Keep the previous value if parsing fails.
            }
        }
        Integer value = spinner.getValue();
        return value == null ? 0 : value;
    }


    private CompletableFuture<List<GameFrame>> attachSimulationHandlers(TrainingSimulationTask task) {
        CompletableFuture<List<GameFrame>> completion = new CompletableFuture<>();
        task.setOnSucceeded(event -> {
            cleanupTaskBindings();
            List<GameFrame> result = task.getValue();
            frames.setAll(result);
            if (!frames.isEmpty()) {
                int lastIndex = frames.size() - 1;
                currentIndex.set(lastIndex);
                currentFrame.set(frames.get(lastIndex));
            } else {
                currentIndex.set(0);
                currentFrame.set(null);
            }
            progressBar.setProgress(1.0);
            statusLabel.setText("Ready");
            completion.complete(result);
        });

        task.setOnFailed(event -> {
            cleanupTaskBindings();
            Throwable error = task.getException();
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            statusLabel.setText(error == null ? "Error" : "Error: " + error.getMessage());
            if (error != null) {
                LOGGER.log(Level.SEVERE, "Simulation failed", error);
                completion.completeExceptionally(error);
            } else {
                completion.completeExceptionally(new IllegalStateException("Unknown simulation error"));
            }
        });

        task.setOnCancelled(event -> {
            cleanupTaskBindings();
            progressBar.setProgress(0);
            statusLabel.setText("Cancelled");
            completion.cancel(false);
        });
        return completion;
    }

    private void cleanupTaskBindings() {
        simulationRunning.set(false);
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        simulationFuture = null;
    }

    private void configureSearchMode(List<String> args) {
        if (args == null) {
            return;
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            String trimmed = arg.trim();
            if (!trimmed.startsWith("--search-mode=")) {
                continue;
            }
            String value = trimmed.substring("--search-mode=".length()).trim();
            if (value.isEmpty()) {
                continue;
            }
            String normalized = value.toUpperCase();
            try {
                searchMode = SearchConstraints.SearchMode.valueOf(normalized);
            } catch (IllegalArgumentException ex) {
                LOGGER.log(Level.WARNING, "Unknown search mode: " + value, ex);
            }
            break;
        }
    }
}
