package com.honeycomb.core.ai;

import com.honeycomb.core.Board;
import com.honeycomb.core.GameState;
import com.honeycomb.core.Symmetry;
import com.honeycomb.core.ai.state.SearchState;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Negamax searcher with depth and time controls.
 */
public final class NegamaxAI implements Searcher {

    private static final Logger LOGGER = Logger.getLogger(NegamaxAI.class.getName());
    private static final int SCORE_WEIGHT = 100;

    private final int maxDepth;
    private final long configuredTimeLimitNanos;
    private final Duration configuredTimeLimit;
    private final SearchState searchState;
    private final TranspositionTable transpositionTable;
    private final long minThinkTimeNanos;

    private long lastVisitedNodes;
    private boolean lastTimedOut;
    private SearchConstraints.SearchMode mode = SearchConstraints.SearchMode.SEQ;
    private long visitedNodes;
    private boolean timedOut;
    private long deadline;

    public NegamaxAI(int maxDepth, Duration timeLimit) {
        this(maxDepth, timeLimit, Duration.ZERO, new TranspositionTable());
    }

    public NegamaxAI(int maxDepth, Duration timeLimit, TranspositionTable table) {
        this(maxDepth, timeLimit, Duration.ZERO, table);
    }

    public NegamaxAI(int maxDepth, Duration timeLimit, Duration minThinkTime) {
        this(maxDepth, timeLimit, minThinkTime, new TranspositionTable());
    }

    public NegamaxAI(int maxDepth, Duration timeLimit, Duration minThinkTime, TranspositionTable table) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("Depth must be at least 1");
        }
        Objects.requireNonNull(timeLimit, "timeLimit");
        Objects.requireNonNull(minThinkTime, "minThinkTime");
        Objects.requireNonNull(table, "table");
        this.maxDepth = maxDepth;
        long nanos = timeLimit.isZero() ? Long.MAX_VALUE : timeLimit.toNanos();
        this.configuredTimeLimitNanos = nanos <= 0L ? 1L : nanos;
        this.configuredTimeLimit = timeLimit.isZero() ? Duration.ZERO : timeLimit;
        long minNanos = minThinkTime.isZero() ? 0L : minThinkTime.toNanos();
        if (minNanos < 0L) {
            throw new IllegalArgumentException("Minimum think time must be non-negative");
        }
        if (minNanos > configuredTimeLimitNanos) {
            throw new IllegalArgumentException("Minimum think time cannot exceed the overall time limit");
        }
        this.minThinkTimeNanos = minNanos;
        this.searchState = new SearchState();
        this.transpositionTable = table;
        CompletableFuture<Void> loadFuture = null;
        if (table.getPersistenceStatus() == TranspositionTable.PersistenceStatus.NOT_LOADED) {
            loadFuture = table.loadFromDiskAsync();
        }
        if (loadFuture != null) {
            loadFuture.whenComplete((ignored, error) -> {
                if (error != null) {
                    LOGGER.log(Level.WARNING, "Failed to load transposition table", error);
                }
            });
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                table.saveToDisk();
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "Failed to save transposition table during shutdown", ex);
            }
        }));
    }

    public SearchConstraints.SearchMode getMode() {
        return mode;
    }

    public void setMode(SearchConstraints.SearchMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public int findBestMove(GameState state) {
        return findBestMove(state, maxDepth);
    }

    public int findBestMove(GameState state, int depthLimit) {
        Objects.requireNonNull(state, "state");

        if (depthLimit < 1) {
            throw new IllegalArgumentException("Depth limit must be at least 1");
        }

        SearchConstraints constraints = new SearchConstraints(depthLimit, configuredTimeLimit, mode);
        SearchResult result = search(state, constraints);
        return result.move();
    }

    public long getLastVisitedNodeCount() {
        return lastVisitedNodes;
    }

    public boolean wasLastSearchTimedOut() {
        return lastTimedOut;
    }

    @Override
    public SearchResult search(GameState state, SearchConstraints constraints) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(constraints, "constraints");

        if (state.isGameOver()) {
            throw new IllegalStateException("Cannot search moves in a terminal position");
        }

        int requestedDepth = constraints.depthLimit();
        int boundedDepth = Math.min(maxDepth, requestedDepth);
        int remainingMoves = Board.CELL_COUNT - state.getBoard().countBits();
        boundedDepth = Math.max(1, Math.min(boundedDepth, remainingMoves));

        long timeLimitNanos = toTimeLimitNanos(constraints.timeLimit());

        SearchConstraints.SearchMode requestedMode = constraints.mode();
        SearchResult result;
        if (requestedMode == SearchConstraints.SearchMode.SEQ) {
            result = sequentialSearch(state, boundedDepth, timeLimitNanos);
        } else {
            LOGGER.warning(() -> String.format("Mode %s is not yet implemented; falling back to SEQ", requestedMode));
            result = sequentialSearch(state, boundedDepth, timeLimitNanos);
        }

        lastVisitedNodes = result.visitedNodes();
        lastTimedOut = result.timedOut();
        return result;
    }

    private long toTimeLimitNanos(Duration timeLimit) {
        long nanos = timeLimit.isZero() ? Long.MAX_VALUE : timeLimit.toNanos();
        return nanos <= 0L ? 1L : nanos;
    }

    private SearchResult sequentialSearch(GameState state, int boundedDepthLimit, long timeLimitNanos) {
        searchState.reset(state);
        visitedNodes = 0L;
        timedOut = false;
        long searchStart = System.nanoTime();
        long now = searchStart;
        deadline = timeLimitNanos == Long.MAX_VALUE ? Long.MAX_VALUE : saturatingAdd(now, timeLimitNanos);

        long boardBits = searchState.currentBoard();
        long rootKey = computeKey(boardBits, searchState.isFirstPlayerTurn());
        TTEntry rootCached = transpositionTable.get(rootKey);
        int ttRootMove = rootCached != null ? rootCached.bestMove() : -1;
        int remainingMoves = Board.CELL_COUNT - state.getBoard().countBits();

        int bestMove = -1;
        int bestScore = Integer.MIN_VALUE;
        int alpha = Integer.MIN_VALUE / 2;
        int beta = Integer.MAX_VALUE / 2;
        int rootAlpha = alpha;

        int rootDepth = searchState.ply();
        int moveCount = searchState.generateMoves(ttRootMove);

        for (int i = 0; i < moveCount; i++) {
            int move = searchState.moveAt(rootDepth, i);
            searchState.pushGenerated(rootDepth, i);
            int score = -negamax(boundedDepthLimit - 1, -beta, -alpha);
            searchState.pop();

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }

            if (timedOut) {
                break;
            }

            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                break;
            }
        }

        if (bestMove < 0) {
            if (moveCount == 0) {
                throw new IllegalStateException("No legal moves available");
            }
            bestMove = searchState.moveAt(rootDepth, 0);
        }

        if (!timedOut && bestScore != Integer.MIN_VALUE) {
            TTFlag flag;
            if (bestScore <= rootAlpha) {
                flag = TTFlag.UPPER_BOUND;
            } else if (bestScore >= beta) {
                flag = TTFlag.LOWER_BOUND;
            } else {
                flag = TTFlag.EXACT;
            }
            transpositionTable.put(rootKey, new TTEntry(bestScore, boundedDepthLimit, flag, bestMove));
        }

        long visitedNodes = this.visitedNodes;
        boolean timedOut = this.timedOut;
        final int usedDepth = boundedDepthLimit;
        LOGGER.info(() -> String.format("Negamax explored %d nodes (depth=%d)", visitedNodes, usedDepth));

        if (!timedOut) {
            enforceMinimumThinkTime(searchStart);
        }

        if (!timedOut && remainingMoves <= 1) {
            transpositionTable.saveToDiskAsync().whenComplete((ignored, error) -> {
                if (error != null) {
                    LOGGER.log(Level.WARNING, "Failed to save transposition table", error);
                }
            });
        }

        return new SearchResult(bestMove, usedDepth, visitedNodes, timedOut);
    }

    private long saturatingAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private void enforceMinimumThinkTime(long searchStart) {
        if (minThinkTimeNanos <= 0L) {
            return;
        }
        long elapsed = System.nanoTime() - searchStart;
        long remaining = minThinkTimeNanos - elapsed;
        if (remaining <= 0L) {
            return;
        }
        LockSupport.parkNanos(remaining);
    }

    private int negamax(int depth, int alpha, int beta) {
        if (isDeadlineExceeded()) {
            timedOut = true;
            return searchState.evaluateCurrent(SCORE_WEIGHT);
        }

        visitedNodes++;

        long boardBits = searchState.currentBoard();
        long key = computeKey(boardBits, searchState.isFirstPlayerTurn());
        int originalAlpha = alpha;
        int originalBeta = beta;
        TTEntry cached = transpositionTable.get(key);
        int ttBestMove = cached != null ? cached.bestMove() : -1;
        if (cached != null && cached.depth() >= depth) {
            switch (cached.flag()) {
                case EXACT:
                    return cached.value();
                case LOWER_BOUND:
                    alpha = Math.max(alpha, cached.value());
                    break;
                case UPPER_BOUND:
                    beta = Math.min(beta, cached.value());
                    break;
                default:
                    break;
            }
            if (alpha >= beta) {
                return cached.value();
            }
        }

        if (depth <= 0 || searchState.isTerminal()) {
            int evaluation = searchState.evaluateCurrent(SCORE_WEIGHT);
            if (!timedOut) {
                transpositionTable.put(key, new TTEntry(evaluation, Math.max(0, depth), TTFlag.EXACT, -1));
            }
            return evaluation;
        }

        int currentPly = searchState.ply();
        int moveCount = searchState.generateMoves(ttBestMove);
        if (moveCount == 0) {
            int evaluation = searchState.evaluateCurrent(SCORE_WEIGHT);
            if (!timedOut) {
                transpositionTable.put(key, new TTEntry(evaluation, Math.max(0, depth), TTFlag.EXACT, -1));
            }
            return evaluation;
        }

        int bestValue = Integer.MIN_VALUE;
        int bestMove = -1;
        boolean isPvNode = (originalBeta - originalAlpha) > 1;

        for (int i = 0; i < moveCount; i++) {
            int move = searchState.moveAt(currentPly, i);
            boolean isCapture = searchState.moveDeltaAt(currentPly, i) > 0;
            int reduction = 0;
            if (!isPvNode && depth > 2 && i > 0 && !isCapture) {
                reduction = 1;
            }

            searchState.pushGenerated(currentPly, i);
            int score;
            if (reduction > 0) {
                int reducedDepth = Math.max(0, depth - 1 - reduction);
                score = -negamax(reducedDepth, -alpha - 1, -alpha);
                if (score > alpha) {
                    score = -negamax(depth - 1, -beta, -alpha);
                }
            } else {
                score = -negamax(depth - 1, -beta, -alpha);
            }
            searchState.pop();

            if (score > bestValue) {
                bestValue = score;
                bestMove = move;
            }
            if (timedOut) {
                return bestValue;
            }

            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                break;
            }
        }

        if (bestValue == Integer.MIN_VALUE) {
            int evaluation = searchState.evaluateCurrent(SCORE_WEIGHT);
            if (!timedOut) {
                transpositionTable.put(key, new TTEntry(evaluation, Math.max(0, depth), TTFlag.EXACT, -1));
            }
            return evaluation;
        }
        if (!timedOut) {
            TTFlag flag;
            if (bestValue <= originalAlpha) {
                flag = TTFlag.UPPER_BOUND;
            } else if (bestValue >= beta) {
                flag = TTFlag.LOWER_BOUND;
            } else {
                flag = TTFlag.EXACT;
            }
            transpositionTable.put(key, new TTEntry(bestValue, depth, flag, bestMove));
        }
        return bestValue;
    }

    private long computeKey(long boardBits, boolean firstPlayerTurn) {
        long canonical = Symmetry.canonical(boardBits);
        long turnBit = firstPlayerTurn ? 1L : 0L;
        return (canonical << 1) | turnBit;
    }

    private boolean isDeadlineExceeded() {
        return timedOut || (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline);
    }
}
