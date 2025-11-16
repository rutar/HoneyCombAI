package com.honeycomb.core.ai;

/**
 * Result payload returned by {@link Searcher} implementations.
 */
public record SearchResult(int move, int depthEvaluated, long visitedNodes, boolean timedOut,
        SearchTelemetry telemetry) {

    public SearchResult {
        telemetry = telemetry == null ? SearchTelemetry.empty() : telemetry;
    }
}
