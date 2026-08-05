package net.puket.lgsolver.solver;

/**
 * The cell states the solver cares about. LootGames' own representation is
 * richer (e.g. question-mark markers, animation frames) — we collapse it
 * down to this enum in {@code LGAccessor#decodeCell}.
 */
public enum CellState {
    /** Not revealed, not marked. The solver may deduce this to be safe or a mine. */
    HIDDEN,
    /** Marked by the player (or the solver via a future auto-flag mode) as a mine. */
    FLAGGED,
    /** Marked "?" — treated as HIDDEN by the solver; we do not trust user question marks. */
    QUESTION,
    /** Revealed empty cell (equivalent to REVEALED with number 0). */
    REVEALED_EMPTY,
    /** Revealed number cell — see {@link Board#number(int, int)}. */
    REVEALED_NUMBER,
    /** Revealed mine. Only ever appears after the player already lost. */
    REVEALED_MINE;

    /** Whether the solver should treat this as an unknown that might be a mine. */
    public boolean isUnknown() {
        return this == HIDDEN || this == QUESTION;
    }
}
