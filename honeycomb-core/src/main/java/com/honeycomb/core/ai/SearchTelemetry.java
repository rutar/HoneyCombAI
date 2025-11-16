package com.honeycomb.core.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated instrumentation data captured during a single {@link Searcher#search} call.
 */
public final class SearchTelemetry {

    private static final SearchTelemetry EMPTY = new SearchTelemetry(List.of());

    private final List<Iteration> iterations;

    public SearchTelemetry(List<Iteration> iterations) {
        if (iterations == null || iterations.isEmpty()) {
            this.iterations = List.of();
        } else {
            this.iterations = Collections.unmodifiableList(new ArrayList<>(iterations));
        }
    }

    public static SearchTelemetry empty() {
        return EMPTY;
    }

    public List<Iteration> iterations() {
        return iterations;
    }

    public Iteration latest() {
        return iterations.isEmpty() ? null : iterations.get(iterations.size() - 1);
    }

    public long totalNodes() {
        return iterations.stream().mapToLong(Iteration::nodes).sum();
    }

    public long totalCutoffs() {
        return iterations.stream().mapToLong(Iteration::cutoffs).sum();
    }

    public long totalTranspositionHits() {
        return iterations.stream().mapToLong(Iteration::transpositionHits).sum();
    }

    public long totalTranspositionStores() {
        return iterations.stream().mapToLong(Iteration::transpositionStores).sum();
    }

    public long totalPvReSearches() {
        return iterations.stream().mapToLong(Iteration::pvReSearches).sum();
    }

    public long maxActiveTasks() {
        return iterations.stream().mapToLong(Iteration::maxActiveTasks).max().orElse(0L);
    }

    public record Iteration(
            int depth,
            long nodes,
            long cutoffs,
            long transpositionHits,
            long transpositionStores,
            long pvReSearches,
            long maxActiveTasks,
            long elapsedNanos,
            List<Integer> principalVariation) {

        public Iteration {
            Objects.requireNonNull(principalVariation, "principalVariation");
            principalVariation = List.copyOf(principalVariation);
        }

        public double elapsedMillis() {
            return elapsedNanos / 1_000_000.0;
        }
    }
}
