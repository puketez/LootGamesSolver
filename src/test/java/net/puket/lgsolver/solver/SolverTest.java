package net.puket.lgsolver.solver;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolverTest {

    private final Solver solver = new Solver();

    @Test
    void singleOneNextToSingleHiddenFlagsIt() {
        Board b = build(3, 1, new String[]{
            "...",
            ".1H",
            "..."
        });
        List<Hint> hints = solver.solve(b);
        assertHint(hints, Hint.Kind.MINE, 2, 1);
    }

    @Test
    void oneAlreadyTouchingFlagMakesOtherNeighboursSafe() {
        Board b = build(3, 1, new String[]{
            "FHH",
            ".1.",
            "..."
        });
        List<Hint> hints = solver.solve(b);
        assertHint(hints, Hint.Kind.SAFE, 1, 0);
        assertHint(hints, Hint.Kind.SAFE, 2, 0);
    }

    @Test
    void oneTwoOnePattern() {
        Board b = build(3, 2, new String[]{
            "HHH",
            "121",
            "..."
        });
        List<Hint> hints = solver.solve(b);
        assertHint(hints, Hint.Kind.MINE, 0, 0);
        assertHint(hints, Hint.Kind.MINE, 2, 0);
        assertHint(hints, Hint.Kind.SAFE, 1, 0);
    }

    @Test
    void emptyFrontierUsesRemainingMineDensity() {
        String[] rows = new String[5];
        Arrays.fill(rows, "HHHHH");
        Board b = build(5, 5, rows);
        List<Hint> hints = solver.solve(b);
        assertEquals(25, hints.size());
        for (Hint h : hints) {
            assertEquals(Hint.Kind.GUESS, h.kind, () -> "expected GUESS got " + h);
            assertEquals(0.20, h.probability, 1e-9);
        }
    }

    @Test
    void freshBoardEveryCellGetsGlobalDensity() {
        String[] rows = new String[13];
        Arrays.fill(rows, "HHHHHHHHHHHHH");
        Board b = build(13, 40, rows);
        List<Hint> hints = solver.solve(b);
        assertEquals(169, hints.size());
        double expected = 40.0 / 169.0;
        for (Hint h : hints) {
            assertEquals(Hint.Kind.GUESS, h.kind);
            assertEquals(expected, h.probability, 1e-9);
        }
    }

    @Test
    void trivialBoardDoesNotCrashOrEmitBogusHints() {
        Board b = build(3, 1, new String[]{
            "F1.",
            "...",
            "..."
        });
        List<Hint> hints = solver.solve(b);
        // Only cells left are already revealed empty (no HIDDEN, no FLAG-to-solve).
        for (Hint h : hints) {
            assertTrue(!(h.x == 0 && h.z == 0),
                () -> "should not re-hint the flagged cell: " + h);
        }
    }

    // --- helpers -------------------------------------------------------------

    static Board build(int size, int totalMines, String[] rows) {
        if (rows.length != size) throw new IllegalArgumentException();
        CellState[] states = new CellState[size * size];
        byte[] numbers = new byte[size * size];
        Arrays.fill(numbers, (byte) -1);
        for (int z = 0; z < size; z++) {
            String row = rows[z];
            if (row.length() != size) throw new IllegalArgumentException("row " + z);
            for (int x = 0; x < size; x++) {
                char c = row.charAt(x);
                int idx = z * size + x;
                switch (c) {
                    case 'H': states[idx] = CellState.HIDDEN; break;
                    case 'F': states[idx] = CellState.FLAGGED; break;
                    case '?': states[idx] = CellState.QUESTION; break;
                    case '.': states[idx] = CellState.REVEALED_EMPTY; break;
                    case '!': states[idx] = CellState.REVEALED_MINE; break;
                    case '0':
                        states[idx] = CellState.REVEALED_EMPTY;
                        numbers[idx] = 0;
                        break;
                    default:
                        if (c >= '1' && c <= '8') {
                            states[idx] = CellState.REVEALED_NUMBER;
                            numbers[idx] = (byte) (c - '0');
                        } else {
                            throw new IllegalArgumentException("bad char " + c);
                        }
                }
            }
        }
        return new Board(size, totalMines, states, numbers);
    }

    private static void assertHint(List<Hint> hints, Hint.Kind kind, int x, int z) {
        assertTrue(hints.stream().anyMatch(h -> h.kind == kind && h.x == x && h.z == z),
            () -> "expected " + kind + " at (" + x + "," + z + ") in " + hints);
    }
}