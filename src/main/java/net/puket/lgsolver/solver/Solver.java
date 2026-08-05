package net.puket.lgsolver.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A three-stage Minesweeper solver:
 *
 * <ol>
 *   <li><b>Constraint propagation.</b> For each revealed number N: if its
 *       hidden-neighbour count equals N minus already-flagged, all those
 *       neighbours are mines. If its flagged-neighbour count equals N, all
 *       other hidden neighbours are safe. Iterate to fixed point.</li>
 *   <li><b>Subset reasoning.</b> If constraint A's hidden set is a subset of
 *       constraint B's, we can subtract A from B and get a tighter constraint.
 *       Catches the classic 1-2-1 pattern and friends without needing full
 *       enumeration.</li>
 *   <li><b>Probability enumeration.</b> Split the frontier into connected
 *       components (cells sharing a constraint). For each component, count —
 *       by constraint-pruned backtracking — how many consistent assignments
 *       place exactly k mines, and how many of those make each cell a mine.
 *       Components are then folded together by polynomial convolution over
 *       the shared mine budget, with a binomial term for the "deep" unknowns
 *       that no constraint touches.</li>
 * </ol>
 *
 * <p>Steps 1-2 are near-linear in the frontier; step 3 is the expensive one,
 * bounded by {@link #NODE_BUDGET}. A component that would exceed it is demoted
 * to "deep" rather than aborting the whole pass.
 *
 * <p>Not thread-safe — the enumerator keeps scratch on the instance.
 */
public final class Solver {

    /** Refuse to enumerate a component wider than this many cells. */
    private static final int MAX_COMPONENT_CELLS = 64;
    /** Search nodes allowed per {@link #solve} call, across all components. */
    private static final long NODE_BUDGET = 1_500_000L;
    /** A constraint is a revealed number's neighbourhood, so never wider than 8. */
    private static final int MAX_CONSTRAINT_CELLS = 8;

    public List<Hint> solve(Board board) {
        int n = board.size();
        int cellCount = n * n;

        boolean[] provenMine = new boolean[cellCount];
        boolean[] provenSafe = new boolean[cellCount];

        // Step 1 + 2: propagate simple + subset constraints until fixed point.
        propagateToFixedPoint(buildConstraints(board, provenMine, provenSafe), provenMine, provenSafe);

        List<Hint> out = new ArrayList<>();
        for (int i = 0; i < cellCount; i++) {
            if (provenMine[i]) out.add(Hint.mine(i % n, i / n));
            else if (provenSafe[i]) out.add(Hint.safe(i % n, i / n));
        }

        // Step 3: probability enumeration over what's still ambiguous.
        // Rebuild constraints because propagation changed which cells are settled.
        annotateProbabilities(board, buildConstraints(board, provenMine, provenSafe),
            provenMine, provenSafe, out);

        return out;
    }

    // --- Constraint construction ---------------------------------------------

    /**
     * A constraint says: exactly {@code target} of {@code cells[0..n)} are
     * mines. "Hidden" here excludes cells the solver has already proven
     * safe/mine. {@code cells} is kept sorted ascending so subset tests are a
     * merge scan rather than a hash lookup.
     */
    private static final class Constraint {
        int target;
        final int[] cells;
        int n;

        Constraint(int target, int[] cells) {
            this.target = target;
            this.cells = cells;
            this.n = cells.length;
        }
    }

    private static List<Constraint> buildConstraints(Board board, boolean[] mine, boolean[] safe) {
        int n = board.size();
        List<Constraint> cs = new ArrayList<>();
        int[] buf = new int[MAX_CONSTRAINT_CELLS];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                if (board.state(x, z) != CellState.REVEALED_NUMBER) continue;
                int target = board.number(x, z);
                int hidden = 0;
                int zLo = z > 0 ? z - 1 : 0, zHi = z < n - 1 ? z + 1 : n - 1;
                int xLo = x > 0 ? x - 1 : 0, xHi = x < n - 1 ? x + 1 : n - 1;
                for (int nz = zLo; nz <= zHi; nz++) {
                    for (int nx = xLo; nx <= xHi; nx++) {
                        if (nx == x && nz == z) continue;
                        int idx = nz * n + nx;
                        CellState s = board.state(nx, nz);
                        if (s == CellState.FLAGGED || mine[idx]) target--;
                        else if (s.isUnknown() && !safe[idx]) buf[hidden++] = idx;
                    }
                }
                // nz-major iteration emits ascending idx, so buf is already sorted.
                if (hidden != 0) cs.add(new Constraint(target, Arrays.copyOf(buf, hidden)));
            }
        }
        return cs;
    }

    // --- Propagation ---------------------------------------------------------

    private static void propagateToFixedPoint(List<Constraint> cs, boolean[] mine, boolean[] safe) {
        int[] diff = new int[MAX_CONSTRAINT_CELLS];
        while (true) {
            simplePass(cs, mine, safe);
            // Subset reasoning is the expensive half, so it only runs once the
            // cheap deductions are exhausted, and it sweeps fully instead of
            // restarting the whole loop on the first hit.
            if (!subsetPass(cs, mine, safe, diff)) return;
        }
    }

    /**
     * Drop settled cells from every constraint, then apply the two trivial
     * deductions (target 0 → all safe, target == width → all mines). Repeats
     * until nothing changes.
     */
    private static void simplePass(List<Constraint> cs, boolean[] mine, boolean[] safe) {
        int m = cs.size();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < m; i++) {
                Constraint c = cs.get(i);
                if (c.n == 0) continue;

                int w = 0;
                for (int r = 0; r < c.n; r++) {
                    int idx = c.cells[r];
                    if (mine[idx]) { c.target--; continue; }
                    if (safe[idx]) continue;
                    c.cells[w++] = idx;
                }
                if (w != c.n) { c.n = w; changed = true; }
                if (c.n == 0) continue;

                if (c.target == 0) {
                    for (int r = 0; r < c.n; r++) {
                        int idx = c.cells[r];
                        if (!safe[idx]) { safe[idx] = true; changed = true; }
                    }
                    c.n = 0;
                } else if (c.target == c.n) {
                    for (int r = 0; r < c.n; r++) {
                        int idx = c.cells[r];
                        if (!mine[idx]) { mine[idx] = true; changed = true; }
                    }
                    c.target = 0;
                    c.n = 0;
                }
            }
        }
    }

    /** If A ⊆ B, then B \ A holds exactly {@code B.target - A.target} mines. */
    private static boolean subsetPass(List<Constraint> cs, boolean[] mine, boolean[] safe, int[] diff) {
        boolean changed = false;
        int m = cs.size();
        for (int i = 0; i < m; i++) {
            Constraint a = cs.get(i);
            if (a.n == 0) continue;
            for (int j = 0; j < m; j++) {
                if (i == j) continue;
                Constraint b = cs.get(j);
                if (b.n <= a.n) continue;
                int dn = subtract(b, a, diff);
                if (dn <= 0) continue;
                int newTarget = b.target - a.target;
                if (newTarget == 0) {
                    for (int k = 0; k < dn; k++) {
                        if (!safe[diff[k]]) { safe[diff[k]] = true; changed = true; }
                    }
                } else if (newTarget == dn) {
                    for (int k = 0; k < dn; k++) {
                        if (!mine[diff[k]]) { mine[diff[k]] = true; changed = true; }
                    }
                }
            }
        }
        return changed;
    }

    /**
     * Merge scan over two ascending cell arrays. Returns the size of B \ A
     * (written to {@code out}), or -1 if A is not a subset of B.
     */
    private static int subtract(Constraint b, Constraint a, int[] out) {
        int ai = 0, dn = 0;
        for (int bi = 0; bi < b.n; bi++) {
            int v = b.cells[bi];
            if (ai < a.n && a.cells[ai] == v) ai++;
            else out[dn++] = v;
        }
        return ai == a.n ? dn : -1;
    }

    // --- Probability enumeration --------------------------------------------

    private void annotateProbabilities(Board board, List<Constraint> cs,
                                       boolean[] mine, boolean[] safe,
                                       List<Hint> out) {
        int n = board.size();
        int cellCount = n * n;

        boolean[] onFrontier = new boolean[cellCount];
        for (int i = 0; i < cs.size(); i++) {
            Constraint c = cs.get(i);
            for (int p = 0; p < c.n; p++) onFrontier[c.cells[p]] = true;
        }

        // "Deep" cells: unknowns no constraint touches. They only see the
        // global mine budget. Skipped components get folded in here too.
        boolean[] isDeep = new boolean[cellCount];
        int deepSize = 0;
        int idx = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++, idx++) {
                if (!board.state(x, z).isUnknown() || mine[idx] || safe[idx]) continue;
                if (!onFrontier[idx]) { isDeep[idx] = true; deepSize++; }
            }
        }
        if (deepSize == 0 && cs.isEmpty()) return;

        int remainingMines = board.totalMines() - board.flaggedCount() - countTrue(mine);
        if (remainingMines < 0) remainingMines = 0;

        nodesLeft = NODE_BUDGET;
        List<Comp> comps = enumerateComponents(cs, cellCount, remainingMines);

        // Components we could not enumerate degrade to deep cells: still
        // hinted, just with the global density instead of an exact figure.
        for (int i = comps.size() - 1; i >= 0; i--) {
            Comp comp = comps.get(i);
            if (comp.weightByK != null) continue;
            for (int b = 0; b < comp.cells.length; b++) {
                int c = comp.cells[b];
                onFrontier[c] = false;
                isDeep[c] = true;
                deepSize++;
            }
            comps.remove(i);
        }

        // Fold the components together: full[K] = ways the whole frontier
        // holds K mines. prefix[i] / suffix[i] let us re-derive "everything
        // except component i" without redoing the convolution from scratch.
        int m = comps.size();
        double[][] prefix = new double[m + 1][];
        double[][] suffix = new double[m + 1][];
        prefix[0] = new double[] { 1.0 };
        for (int i = 0; i < m; i++) prefix[i + 1] = convolve(prefix[i], comps.get(i).weightByK);
        suffix[m] = new double[] { 1.0 };
        for (int i = m - 1; i >= 0; i--) suffix[i] = convolve(comps.get(i).weightByK, suffix[i + 1]);

        double[] full = prefix[m];
        double[] deepWeight = binomialRow(deepSize, remainingMines, full.length);

        double totalWeight = 0.0;
        double expectedDeepMines = 0.0;
        for (int k = 0; k < full.length; k++) {
            double w = full[k] * deepWeight[k];
            if (w == 0.0) continue;
            totalWeight += w;
            expectedDeepMines += w * (remainingMines - k);
        }
        if (!(totalWeight > 0.0)) return; // inconsistent board (bad flags) — stay quiet

        for (int i = 0; i < m; i++) {
            Comp comp = comps.get(i);
            double[] others = convolve(prefix[i], suffix[i + 1]);
            // perK[k] = weight of "this component holds k mines" across
            // everything else (other components + the deep binomial).
            double[] perK = new double[comp.weightByK.length];
            for (int k = 0; k < perK.length; k++) {
                double acc = 0.0;
                for (int j = 0; j < others.length; j++) {
                    int t = k + j;
                    if (t >= deepWeight.length) break;
                    acc += others[j] * deepWeight[t];
                }
                perK[k] = acc;
            }
            for (int b = 0; b < comp.cells.length; b++) {
                double mineWeight = 0.0;
                for (int k = 0; k < perK.length; k++) {
                    double f = comp.cellMineByK[k][b];
                    if (f != 0.0) mineWeight += f * perK[k];
                }
                emit(out, n, comp.cells[b], mineWeight / totalWeight, mine, safe);
            }
        }

        if (deepSize > 0) {
            double p = expectedDeepMines / totalWeight / deepSize;
            for (int c = 0; c < cellCount; c++) {
                if (isDeep[c]) emit(out, n, c, p, mine, safe);
            }
        }
    }

    private static void emit(List<Hint> out, int n, int cell, double p, boolean[] mine, boolean[] safe) {
        if (p <= 1e-9) {
            if (!safe[cell]) out.add(Hint.safe(cell % n, cell / n));
        } else if (p >= 1 - 1e-9) {
            if (!mine[cell]) out.add(Hint.mine(cell % n, cell / n));
        } else {
            out.add(Hint.guess(cell % n, cell / n, p));
        }
    }

    private static int countTrue(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    /** Polynomial multiply over mine counts. Both inputs are short (≤ frontier size). */
    private static double[] convolve(double[] a, double[] b) {
        double[] r = new double[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++) {
            double ai = a[i];
            if (ai == 0.0) continue;
            for (int j = 0; j < b.length; j++) r[i + j] += ai * b[j];
        }
        return r;
    }

    /**
     * {@code row[k] = C(deepSize, remaining - k)} — the number of ways to park
     * the leftover mines in the deep region when the frontier takes k. Scaled
     * by the row maximum; every consumer only ever looks at ratios, and the
     * raw binomials on a 19x19 board run to ~1e77.
     */
    private static double[] binomialRow(int deepSize, int remaining, int len) {
        double[] row = new double[len];
        double max = 0.0;
        for (int k = 0; k < len; k++) {
            double v = binomial(deepSize, remaining - k);
            row[k] = v;
            if (v > max) max = v;
        }
        if (max > 0.0) for (int k = 0; k < len; k++) row[k] /= max;
        return row;
    }

    private static double binomial(int n, int k) {
        if (k < 0 || k > n) return 0.0;
        if (k == 0 || k == n) return 1.0;
        k = Math.min(k, n - k);
        double r = 1.0;
        for (int i = 1; i <= k; i++) {
            r *= (double) (n - k + i) / (double) i;
        }
        return r;
    }

    // --- Per-component enumeration -------------------------------------------

    /** One connected frontier region, with its solution counts normalised to 1. */
    private static final class Comp {
        int[] cells;
        /** {@code weightByK[k]} = share of solutions placing k mines. Null if skipped. */
        double[] weightByK;
        /** {@code cellMineByK[k][b]} = share of those that make cell b a mine. */
        double[][] cellMineByK;
    }

    // Enumerator scratch, reused for every component in a solve.
    private long nodesLeft;
    private int[] cTarget, cAssigned, cRemaining;
    private int[] ccStart, ccList;
    private int compCellCount, mineCap;
    private long[] countsByK;
    private long[][] cellCountsByK;
    private long[][] undoCounts;

    private List<Comp> enumerateComponents(List<Constraint> cs, int cellCount, int remainingMines) {
        int m = cs.size();
        List<Comp> out = new ArrayList<>();
        if (m == 0) return out;

        // Union-find over constraints: two are connected if they share a cell.
        int[] parent = new int[m];
        for (int i = 0; i < m; i++) parent[i] = i;
        int[] owner = new int[cellCount];
        Arrays.fill(owner, -1);
        for (int i = 0; i < m; i++) {
            Constraint c = cs.get(i);
            for (int p = 0; p < c.n; p++) {
                int cell = c.cells[p];
                if (owner[cell] < 0) owner[cell] = i;
                else union(parent, owner[cell], i);
            }
        }

        // Bucket the constraints by root, preserving order within a component
        // so the cell layout below still closes constraints early.
        int[] rootToBucket = new int[m];
        Arrays.fill(rootToBucket, -1);
        List<List<Constraint>> buckets = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            int root = find(parent, i);
            int bucket = rootToBucket[root];
            if (bucket < 0) {
                bucket = buckets.size();
                rootToBucket[root] = bucket;
                buckets.add(new ArrayList<Constraint>());
            }
            buckets.get(bucket).add(cs.get(i));
        }

        int[] local = new int[cellCount];
        Arrays.fill(local, -1);
        for (int i = 0; i < buckets.size(); i++) out.add(enumerate(buckets.get(i), local, remainingMines));
        return out;
    }

    /**
     * Count, for this component, how many consistent mine assignments place
     * exactly k mines and how many of those make each cell a mine — by
     * backtracking with constraint pruning, never materialising an assignment.
     *
     * <p>The per-cell tallies come from a subtraction trick: after recursing
     * into "cell d is a mine", whatever {@code countsByK} gained is exactly the
     * set of solutions in which d is a mine. That is O(maxK) per branch instead
     * of O(cells) per solution.
     */
    private Comp enumerate(List<Constraint> comp, int[] local, int remainingMines) {
        Comp result = new Comp();

        // Lay cells out in constraint order so each constraint's cells are
        // contiguous — the earlier a constraint fills up, the earlier we prune.
        int nc = comp.size();
        int width = 0;
        for (int i = 0; i < nc; i++) width += comp.get(i).n;
        int[] cells = new int[width];
        int count = 0;
        for (int i = 0; i < nc; i++) {
            Constraint c = comp.get(i);
            for (int p = 0; p < c.n; p++) {
                int cell = c.cells[p];
                if (local[cell] < 0) { local[cell] = count; cells[count++] = cell; }
            }
        }
        result.cells = Arrays.copyOf(cells, count);

        if (count > MAX_COMPONENT_CELLS) {
            for (int b = 0; b < count; b++) local[result.cells[b]] = -1;
            return result; // weightByK stays null → caller demotes to deep
        }

        // Cell → constraints it belongs to, as a flat CSR-style index.
        int[] degree = new int[count + 1];
        for (int i = 0; i < nc; i++) {
            Constraint c = comp.get(i);
            for (int p = 0; p < c.n; p++) degree[local[c.cells[p]]]++;
        }
        ccStart = new int[count + 1];
        for (int b = 0; b < count; b++) ccStart[b + 1] = ccStart[b] + degree[b];
        ccList = new int[ccStart[count]];
        int[] fill = new int[count];
        for (int i = 0; i < nc; i++) {
            Constraint c = comp.get(i);
            for (int p = 0; p < c.n; p++) {
                int b = local[c.cells[p]];
                ccList[ccStart[b] + fill[b]++] = i;
            }
        }

        cTarget = new int[nc];
        cAssigned = new int[nc];
        cRemaining = new int[nc];
        for (int i = 0; i < nc; i++) {
            cTarget[i] = comp.get(i).target;
            cRemaining[i] = comp.get(i).n;
        }

        compCellCount = count;
        mineCap = Math.min(count, remainingMines);
        countsByK = new long[mineCap + 1];
        cellCountsByK = new long[mineCap + 1][count];
        undoCounts = new long[count][mineCap + 1];

        boolean complete = dfs(0, 0);

        for (int b = 0; b < count; b++) local[result.cells[b]] = -1;
        if (!complete) return result; // ran out of budget → demote to deep

        long total = 0;
        for (long v : countsByK) total += v;
        if (total == 0) return result; // unsatisfiable (bad flags) → demote

        double inv = 1.0 / total;
        result.weightByK = new double[mineCap + 1];
        result.cellMineByK = new double[mineCap + 1][count];
        for (int k = 0; k <= mineCap; k++) {
            result.weightByK[k] = countsByK[k] * inv;
            for (int b = 0; b < count; b++) result.cellMineByK[k][b] = cellCountsByK[k][b] * inv;
        }
        return result;
    }

    /** @return false if the node budget ran out (results are then incomplete). */
    private boolean dfs(int depth, int minesSoFar) {
        if (--nodesLeft < 0) return false;
        if (depth == compCellCount) {
            countsByK[minesSoFar]++;
            return true;
        }

        boolean ok = true;
        if (place(depth, false)) ok = dfs(depth + 1, minesSoFar);
        unplace(depth, false);
        if (!ok) return false;

        if (minesSoFar < mineCap) {
            if (place(depth, true)) {
                long[] before = undoCounts[depth];
                System.arraycopy(countsByK, 0, before, 0, countsByK.length);
                ok = dfs(depth + 1, minesSoFar + 1);
                long[] tally = null;
                for (int k = minesSoFar + 1; k < countsByK.length; k++) {
                    long delta = countsByK[k] - before[k];
                    if (delta != 0) {
                        tally = cellCountsByK[k];
                        tally[depth] += delta;
                    }
                }
            }
            unplace(depth, true);
            if (!ok) return false;
        }
        return true;
    }

    /** @return true if every touched constraint is still satisfiable. */
    private boolean place(int depth, boolean isMine) {
        boolean ok = true;
        for (int p = ccStart[depth]; p < ccStart[depth + 1]; p++) {
            int c = ccList[p];
            cRemaining[c]--;
            if (isMine) cAssigned[c]++;
            if (cAssigned[c] > cTarget[c] || cAssigned[c] + cRemaining[c] < cTarget[c]) ok = false;
        }
        return ok;
    }

    private void unplace(int depth, boolean isMine) {
        for (int p = ccStart[depth]; p < ccStart[depth + 1]; p++) {
            int c = ccList[p];
            cRemaining[c]++;
            if (isMine) cAssigned[c]--;
        }
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[ra] = rb;
    }
}
