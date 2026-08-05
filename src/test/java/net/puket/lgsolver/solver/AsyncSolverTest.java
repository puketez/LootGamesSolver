package net.puket.lgsolver.solver;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers the client-thread side of the handoff: results arrive, unchanged
 * boards don't trigger redundant solves, and a burst of submissions settles on
 * the newest board rather than working through a backlog.
 */
class AsyncSolverTest {

    private static final long TIMEOUT_MS = 5000;

    @Test
    void resultEventuallyArrivesAndMatchesTheInlineSolver() {
        Board b = SolverTest.build(3, 2, new String[]{
            "HHH",
            "121",
            "..."
        });
        AsyncSolver async = new AsyncSolver();
        assertNull(async.latest(), "nothing solved before the first submit");

        async.submit(b);
        AsyncSolver.Result r = await(async, b);

        assertSame(b, r.board, "the result carries the exact board it solved");
        assertEquals(render(new Solver().solve(b)), render(r.hints),
            "off-thread hints must match what the inline solver produces");
    }

    @Test
    void resubmittingAnEqualBoardDoesNotResolve() throws Exception {
        Board b = SolverTest.build(3, 1, new String[]{
            "...",
            ".1H",
            "..."
        });
        AsyncSolver async = new AsyncSolver();
        async.submit(b);
        AsyncSolver.Result first = await(async, b);

        // A distinct but equal Board is what refreshMS hands over every tick.
        Board same = SolverTest.build(3, 1, new String[]{
            "...",
            ".1H",
            "..."
        });
        for (int i = 0; i < 50; i++) async.submit(same);
        Thread.sleep(150);

        assertSame(first, async.latest(),
            "an unchanged board must not produce a second Result");
    }

    @Test
    void burstOfSubmissionsSettlesOnTheNewestBoard() {
        AsyncSolver async = new AsyncSolver();
        Board last = null;
        // Each board differs, so each submit is a genuine new job. The worker
        // must end up on the final one, not grind through every intermediate.
        for (int i = 0; i < 40; i++) {
            last = randomBoard(9, 10, i);
            async.submit(last);
        }
        AsyncSolver.Result r = await(async, last);
        assertEquals(last, r.board, "settles on the most recently submitted board");
    }

    @Test
    void resetDropsEverythingAndAllowsResolvingTheSameBoard() {
        Board b = SolverTest.build(3, 1, new String[]{
            "FHH",
            ".1.",
            "..."
        });
        AsyncSolver async = new AsyncSolver();
        async.submit(b);
        AsyncSolver.Result first = await(async, b);

        async.reset();
        assertNull(async.latest(), "reset clears the published result");

        async.submit(b);
        AsyncSolver.Result second = await(async, b);
        assertTrue(first != second, "after reset the same board solves again");
        assertEquals(render(first.hints), render(second.hints));
    }

    @Test
    void survivesAStage4SizedBoardWithoutBlockingTheCaller() {
        Board big = randomBoard(19, 68, 4242);
        AsyncSolver async = new AsyncSolver();

        long t0 = System.nanoTime();
        async.submit(big);
        double submitMs = (System.nanoTime() - t0) / 1e6;

        // The whole point: submit returns immediately regardless of board cost.
        assertTrue(submitMs < 5.0, "submit must not block the client thread, took " + submitMs + "ms");
        assertNotNull(await(async, big));
    }

    // --- helpers -------------------------------------------------------------

    private static AsyncSolver.Result await(AsyncSolver async, Board expected) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            AsyncSolver.Result r = async.latest();
            if (r != null && r.board.equals(expected)) return r;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for a solve");
            }
        }
        fail("no result for the expected board within " + TIMEOUT_MS + "ms");
        return null;
    }

    private static String render(List<Hint> hints) {
        String[] lines = new String[hints.size()];
        for (int i = 0; i < hints.size(); i++) {
            Hint h = hints.get(i);
            lines[i] = h.kind + " " + h.x + "," + h.z + " "
                + String.format(java.util.Locale.ROOT, "%.9f", h.probability);
        }
        Arrays.sort(lines);
        return String.join("\n", lines);
    }

    /** Deterministic pseudo-board: enough revealed numbers to give the solver work. */
    private static Board randomBoard(int size, int mines, int seed) {
        java.util.Random rnd = new java.util.Random(seed);
        boolean[] mine = new boolean[size * size];
        int placed = 0;
        while (placed < mines) {
            int i = rnd.nextInt(size * size);
            if (!mine[i]) { mine[i] = true; placed++; }
        }
        CellState[] states = new CellState[size * size];
        byte[] numbers = new byte[size * size];
        Arrays.fill(numbers, (byte) -1);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int idx = z * size + x;
                if (mine[idx]) { states[idx] = CellState.HIDDEN; continue; }
                if (rnd.nextInt(100) < 55) { states[idx] = CellState.HIDDEN; continue; }
                int c = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = x + dx, nz = z + dz;
                        if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                        if (mine[nz * size + nx]) c++;
                    }
                }
                if (c == 0) states[idx] = CellState.REVEALED_EMPTY;
                else { states[idx] = CellState.REVEALED_NUMBER; numbers[idx] = (byte) c; }
            }
        }
        return new Board(size, mines, states, numbers);
    }
}
