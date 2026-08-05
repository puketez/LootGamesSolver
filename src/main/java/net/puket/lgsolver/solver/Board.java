package net.puket.lgsolver.solver;

import java.util.Arrays;

/**
 * Immutable snapshot of a Minesweeper board as visible to the client at some
 * instant. Rows are indexed by z (north-south), columns by x (east-west),
 * both zero-based. Row 0 / column 0 corresponds to the north-west corner —
 * matching the LootGames master TileEntity's position.
 *
 * <p>The defensive copies in the constructor are load-bearing, not hygiene:
 * a Board is handed to the solver thread (see
 * {@link net.puket.lgsolver.solver.AsyncSolver}) and read there while the
 * client thread carries on. Nothing may alias its arrays afterwards.
 */
public final class Board {
    private final int size;
    private final int totalMines;
    private final CellState[] states;
    /** Number 0..8 for REVEALED_NUMBER cells, -1 otherwise. */
    private final byte[] numbers;

    public Board(int size, int totalMines, CellState[] states, byte[] numbers) {
        if (size <= 0) throw new IllegalArgumentException("size must be positive");
        int cells = size * size;
        if (states.length != cells || numbers.length != cells) {
            throw new IllegalArgumentException("states/numbers length must match size*size");
        }
        this.size = size;
        this.totalMines = totalMines;
        this.states = states.clone();
        this.numbers = numbers.clone();
    }

    public int size() { return size; }
    public int totalMines() { return totalMines; }

    public CellState state(int x, int z) { return states[idx(x, z)]; }
    /** Returns 0..8, or -1 if the cell is not a revealed number. */
    public int number(int x, int z) { return numbers[idx(x, z)]; }

    public int idx(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) {
            throw new IndexOutOfBoundsException("(" + x + ", " + z + ")");
        }
        return z * size + x;
    }

    public int flaggedCount() {
        int n = 0;
        for (CellState s : states) if (s == CellState.FLAGGED) n++;
        return n;
    }

    public int hiddenCount() {
        int n = 0;
        for (CellState s : states) if (s.isUnknown()) n++;
        return n;
    }

    /** Debug ASCII dump — H hidden, F flag, ? question, . empty, 1-8 number, ! mine. */
    public String toAscii() {
        StringBuilder sb = new StringBuilder(size * (size + 1));
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                CellState s = state(x, z);
                switch (s) {
                    case HIDDEN:          sb.append('H'); break;
                    case FLAGGED:         sb.append('F'); break;
                    case QUESTION:        sb.append('?'); break;
                    case REVEALED_EMPTY:  sb.append('.'); break;
                    case REVEALED_MINE:   sb.append('!'); break;
                    case REVEALED_NUMBER: {
                        int n = number(x, z);
                        sb.append(n >= 0 && n <= 9 ? (char) ('0' + n) : '?');
                        break;
                    }
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Override public String toString() {
        return "Board{" + size + "x" + size + ", mines=" + totalMines
            + ", flagged=" + flaggedCount() + ", hidden=" + hiddenCount() + "}";
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Board)) return false;
        Board b = (Board) o;
        return size == b.size && totalMines == b.totalMines
            && Arrays.equals(states, b.states) && Arrays.equals(numbers, b.numbers);
    }
    @Override public int hashCode() {
        return Arrays.hashCode(states) * 31 + Arrays.hashCode(numbers);
    }
}
