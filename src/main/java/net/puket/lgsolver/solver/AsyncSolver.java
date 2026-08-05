package net.puket.lgsolver.solver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;

/**
 * Runs {@link Solver} on a background daemon thread.
 *
 * <p>Minecraft's client tick and rendering share a thread, so a hard board
 * (19x19 costs tens of ms) would stall frames if solved inline. Hints land a
 * tick later instead, which is invisible on a static overlay.
 *
 * <p><b>Contract:</b> only immutable data crosses the boundary — a
 * {@link Board} in, a {@link Result} out — so no Minecraft object is ever
 * touched off the client thread. The reflective TileEntity read stays on the
 * tick; only its snapshot is handed over. The shared surface is one
 * {@code volatile} reference.
 *
 * <p>{@link #submit} is newest-wins, so a burst of clicks never builds a
 * backlog of stale positions.
 */
public final class AsyncSolver {

    private static final Logger LOG = LogManager.getLogger("lgsolver");

    /** A solved board. Immutable; published to the client thread as one unit. */
    public static final class Result {
        public final Board board;
        public final List<Hint> hints;

        Result(Board board, List<Hint> hints) {
            this.board = board;
            this.hints = hints;
        }
    }

    private final Object lock = new Object();

    /** Newest board awaiting a solve. Guarded by {@link #lock}. */
    private Board pending;
    /** Last board handed to {@link #submit}. Client thread only — no sync needed. */
    private Board submitted;

    private volatile Result latest;
    private Thread worker;

    /** Most recent finished solve, or null if none has completed yet. */
    public Result latest() {
        return latest;
    }

    /**
     * Queue a board for solving, unless it is identical to the last one
     * submitted. Cheap enough to call every tick: the equality check is an
     * {@code Arrays.equals} pair that bails on the first differing cell.
     */
    public void submit(Board board) {
        if (board == null || board.equals(submitted)) return;
        submitted = board;
        synchronized (lock) {
            pending = board;
            ensureWorker();
            lock.notify();
        }
    }

    /** Drop every reference — called when the board unbinds or hints go off. */
    public void reset() {
        synchronized (lock) {
            pending = null;
        }
        submitted = null;
        latest = null;
    }

    private void ensureWorker() {
        if (worker != null) return;
        worker = new Thread(this::loop, "LGSolver-solve");
        // Daemon so it can never hold up game exit; min priority so it yields
        // to the render thread on a busy machine.
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private void loop() {
        // Confined to this thread: Solver keeps enumeration scratch on itself.
        Solver solver = new Solver();
        while (true) {
            Board job;
            synchronized (lock) {
                while (pending == null) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                job = pending;
                pending = null;
            }
            try {
                List<Hint> hints = solver.solve(job);
                latest = new Result(job, hints == null ? Collections.<Hint>emptyList() : hints);
            } catch (Throwable t) {
                // A solver bug must not take the game down or wedge the thread.
                LOG.warn("LGSolver: solve failed; dropping hints for this board", t);
                latest = new Result(job, Collections.<Hint>emptyList());
            }
        }
    }
}
