package com.honeycomb.core.ai;

import com.honeycomb.core.GameState;

/**
 * Generic interface for game tree search implementations.
 */
public interface Searcher {

    /**
     * Executes a search for the best move in the provided {@link GameState} under the supplied
     * {@link SearchConstraints}.
     *
     * @param state the starting state to analyse
     * @param constraints the limits guiding the search execution
     * @return the result of the search
     */
    SearchResult search(GameState state, SearchConstraints constraints);
}
