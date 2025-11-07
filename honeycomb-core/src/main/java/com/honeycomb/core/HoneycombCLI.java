package com.honeycomb.core;

import java.util.Scanner;

/**
 * Simple console front-end for playing a full Honeycomb match between two humans.
 */
public final class HoneycombCLI {

    private HoneycombCLI() {
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        GameState state = new GameState();

        System.out.println("Honeycomb — console edition");
        while (!state.isGameOver()) {
            printBoard(state);
            System.out.printf("Scores — Player 1: %d, Player 2: %d%n", state.getScore(true), state.getScore(false));
            System.out.printf("Player %d, choose a cell (0-54): ", (state.getBoard().isFirstPlayer() ? 1 : 0) + 1);

            String input = scanner.nextLine().trim();
            int cellIndex;
            try {
                cellIndex = Integer.parseInt(input);
            } catch (NumberFormatException ex) {
                System.out.println("Please enter a valid cell index.");
                continue;
            }

            if (cellIndex < 0 || cellIndex >= Board.CELL_COUNT) {
                System.out.println("Cell index must be between 0 and 54.");
                continue;
            }
            if (!state.getBoard().isEmpty(cellIndex)) {
                System.out.println("Cell is already occupied. Choose another one.");
                continue;
            }

            state = state.applyMove(cellIndex);
        }

        printBoard(state);
        System.out.printf("Final scores — Player 1: %d, Player 2: %d%n", state.getScore(true), state.getScore(false));
        System.out.printf("Winner: Player %d%n", state.getScore(true) > state.getScore(false) ? 1 : 2);
    }

    private static void printBoard(GameState state) {
        Board board = state.getBoard();
        int index = 0;
        for (int row = 0; row < ScoreCalculator.BOARD_HEIGHT; row++) {
            int padding = ScoreCalculator.BOARD_HEIGHT - row - 1;
            for (int i = 0; i < padding; i++) {
                System.out.print("   ");
            }
            for (int col = 0; col <= row; col++) {
                boolean empty = board.isEmpty(index);
                String label = empty ? String.format("%02d", index) : "XX";
                System.out.print(label + " ");
                index++;
            }
            System.out.println();
        }
    }
}
