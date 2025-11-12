package com.honeycomb.core.ai;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable search configuration passed to {@link Searcher} implementations.
 */
public record SearchConstraints(int depthLimit, Duration timeLimit, SearchMode mode) {

    public SearchConstraints {
        Objects.requireNonNull(timeLimit, "timeLimit");
        Objects.requireNonNull(mode, "mode");
        if (depthLimit < 1) {
            throw new IllegalArgumentException("depthLimit must be at least 1");
        }
        if (timeLimit.isNegative()) {
            throw new IllegalArgumentException("timeLimit must not be negative");
        }
    }

    /**
     * Execution strategy hint for {@link Searcher} implementations.
     */
    public enum SearchMode {
        SEQ,
        PAR
    }
}
