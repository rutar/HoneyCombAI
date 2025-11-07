# HoneyCombAI

HoneyCombAI is a Java implementation of the Honeycomb board game. It provides a 55-cell
bitboard model of the triangular arena, deterministic scoring for every possible line,
and an immutable game state that tracks turns and line completions. Future milestones
expand the engine with Negamax search, transposition tables, training utilities, and
visualisations.

## Project layout

### `honeycomb-core`

The core module currently delivers the foundational game rules and a console interface.
It includes:

- Bit-board representation of the 55-cell triangular field.
- Deterministic score calculation across the 30 scoring lines.
- Immutable `GameState` with turn tracking and score updates.
- Console UI that allows two human players to complete a full match.
- JUnit 5 tests that cover the board, scoring, and game flow behaviour.

## Building and testing

The repository uses the Gradle Kotlin DSL. Install JDK 17+ along with a Gradle 8+
distribution, then run the following commands from the project root.

Run the automated test suite:

```bash
gradle test
```

Execute the console edition of the game:

```bash
gradle :honeycomb-core:run
```

The `run` task launches `com.honeycomb.core.HoneycombCLI`, which guides two human
players through all 55 moves while displaying the evolving score.
