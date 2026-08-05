package net.puket.lgsolver.gol;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-tick memory of the current round's sequence + player progress.
 *
 * <p>The sequence is only visible to the client during the "show_sequence"
 * stage. We snapshot it there and keep it through "waiting_for_sequence" so
 * the overlay can highlight only the next expected cell. Progress advances
 * from client-observed clicks (see ClientEvents.onPlayerInteract).
 */
public final class GOLState {
    public int originX, originY, originZ;
    public int size = 3;
    public final List<int[]> sequence = new ArrayList<>();
    public String stageId = "";
    /** Player's click progress. 0 = next expected is sequence[0]. */
    public int progress = 0;

    public boolean hasSequence() { return !sequence.isEmpty(); }

    public int[] nextExpected() {
        if (progress < 0 || progress >= sequence.size()) return null;
        return sequence.get(progress);
    }

    public void applyFromShow(GOLAccessor.Snapshot snap) {
        originX = snap.originX; originY = snap.originY; originZ = snap.originZ;
        size = snap.size;
        stageId = snap.stageId;
        if (!snap.sequence.isEmpty() && !sameSequence(snap.sequence)) {
            sequence.clear();
            sequence.addAll(snap.sequence);
            progress = 0;
            if (net.puket.lgsolver.LGSolver.config.debugLogging) {
                net.puket.lgsolver.LGSolver.LOG.info("LGSolver-GOL: captured sequence of {} symbols", sequence.size());
            }
        }
    }

    public void applyFromWaiting(GOLAccessor.Snapshot snap) {
        originX = snap.originX; originY = snap.originY; originZ = snap.originZ;
        size = snap.size;
        String prev = stageId;
        stageId = snap.stageId;
        if (!"waiting_for_sequence".equals(prev)) progress = 0;
        // Only adopt one if we never captured any (joined mid-round, or a save
        // was reloaded). Overwriting a capture would invalidate `progress`.
        if (sequence.isEmpty() && !snap.sequence.isEmpty()) {
            sequence.addAll(snap.sequence);
            progress = 0;
        }
    }

    public void clear() {
        sequence.clear();
        stageId = "";
        progress = 0;
    }

    private boolean sameSequence(List<int[]> other) {
        if (sequence.size() != other.size()) return false;
        for (int i = 0; i < sequence.size(); i++) {
            int[] a = sequence.get(i), b = other.get(i);
            if (a[0] != b[0] || a[1] != b[1]) return false;
        }
        return true;
    }
}
