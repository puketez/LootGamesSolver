package net.puket.lgsolver.solver;

/** A per-cell recommendation produced by {@link Solver}. */
public final class Hint {
    public enum Kind {
        /** Provably safe. Left-click. */
        SAFE,
        /** Provably a mine. Right-click to flag. */
        MINE,
        /** No proof either way; {@link Hint#probability} is our best estimate this cell is a mine. */
        GUESS
    }

    public final int x, z;
    public final Kind kind;
    /** 0.0 = definitely safe, 1.0 = definitely a mine, in between = guess. */
    public final double probability;

    public Hint(int x, int z, Kind kind, double probability) {
        this.x = x; this.z = z; this.kind = kind; this.probability = probability;
    }

    public static Hint safe(int x, int z)   { return new Hint(x, z, Kind.SAFE, 0.0); }
    public static Hint mine(int x, int z)   { return new Hint(x, z, Kind.MINE, 1.0); }
    public static Hint guess(int x, int z, double p) { return new Hint(x, z, Kind.GUESS, p); }

    @Override public String toString() {
        return kind + "(" + x + ", " + z + ", p=" + String.format("%.2f", probability) + ")";
    }
}
