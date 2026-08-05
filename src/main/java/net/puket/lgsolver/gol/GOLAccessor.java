package net.puket.lgsolver.gol;

import net.puket.lgsolver.LGSolver;
import net.minecraft.tileentity.TileEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads Game-of-Light (Simon Says) state from a GOLMasterTile.
 * The player-visible sequence is only broadcast to the client during the
 * "showing_sequence" stage (that stage's {@code serialize} writes it
 * unconditionally). We snapshot it then and hand it out on demand.
 */
public final class GOLAccessor {

    private static final String[] MASTER_TE_CLASSES = new String[] {
        "ru.timeconqueror.lootgames.common.block.tile.GOLMasterTile",
    };

    private Class<?> masterTeClass;
    private Field teGameField;
    private Field gameStageField;
    private Field stageSequenceField;
    private Class<?> knownStageClass;

    private Method getBoardOriginMethod;
    private Method blockPosGetX, blockPosGetY, blockPosGetZ;
    private Method symbolGetPos;
    private Method pos2iGetX, pos2iGetY;

    private String warnedAbout;

    public GOLAccessor() { resolve(); }

    public boolean isPresent() { return masterTeClass != null; }

    public boolean isMaster(TileEntity te) {
        return te != null && masterTeClass != null && masterTeClass.isInstance(te);
    }

    public String describe() {
        return masterTeClass == null
            ? "GOL: not bound"
            : "GOL: class=" + masterTeClass.getName()
              + ", stageSequenceField=" + (stageSequenceField == null ? "?" : stageSequenceField.getDeclaringClass().getSimpleName());
    }

    /** Snapshot of a Game-of-Light board. */
    public static final class Snapshot {
        public final int originX, originY, originZ;
        public final int size;
        public final String stageId;
        /** Sequence in click order; each entry is a (gx, gz) grid position (0..2). */
        public final List<int[]> sequence;

        Snapshot(int ox, int oy, int oz, int size, String stageId, List<int[]> seq) {
            this.originX = ox; this.originY = oy; this.originZ = oz;
            this.size = size; this.stageId = stageId; this.sequence = seq;
        }
    }

    public Snapshot tryReadSnapshot(TileEntity te) {
        if (!isMaster(te)) return null;
        try {
            Object game = teGameField.get(te);
            if (game == null) return null;
            Object stage = gameStageField.get(game);
            if (stage == null) return null;

            String stageId = "";
            try {
                Method getID = stage.getClass().getMethod("getID");
                Object id = getID.invoke(stage);
                if (id != null) stageId = id.toString();
            } catch (NoSuchMethodException ignored) {}

            List<int[]> seq = new ArrayList<>();
            // Read `sequence` on any stage that has one. During SHOW it's
            // guaranteed to be synced; during WAITING it's usually empty on
            // client (server only writes it in SAVE serialize) but if a save
            // was reloaded, it may be present — take it either way.
            if (knownStageClass != stage.getClass()) {
                stageSequenceField = findField(stage.getClass(), "sequence");
                knownStageClass = stage.getClass();
            }
            if (stageSequenceField != null) {
                Object seqList = stageSequenceField.get(stage);
                if (seqList instanceof List<?>) {
                    for (Object symbol : (List<?>) seqList) {
                        int[] xy = symbolToXY(symbol);
                        if (xy != null) seq.add(xy);
                    }
                }
            }

            int ox = te.xCoord, oy = te.yCoord, oz = te.zCoord;
            if (getBoardOriginMethod == null) {
                getBoardOriginMethod = findGetter(game.getClass(), "getBoardOrigin");
            }
            if (getBoardOriginMethod != null) {
                Object origin = getBoardOriginMethod.invoke(game);
                if (origin != null) {
                    ensureBlockPosMethods(origin.getClass());
                    if (blockPosGetX != null) {
                        ox = (Integer) blockPosGetX.invoke(origin);
                        oy = (Integer) blockPosGetY.invoke(origin);
                        oz = (Integer) blockPosGetZ.invoke(origin);
                    }
                }
            }
            return new Snapshot(ox, oy, oz, 3, stageId, seq);
        } catch (Throwable t) {
            warnOnce("GOL tryReadSnapshot: " + t, t);
            return null;
        }
    }

    private int[] symbolToXY(Object symbol) throws Exception {
        if (symbol == null) return null;
        if (symbolGetPos == null) symbolGetPos = findGetter(symbol.getClass(), "getPos");
        if (symbolGetPos == null) return null;
        Object pos = symbolGetPos.invoke(symbol);
        if (pos == null) return null;
        if (pos2iGetX == null) pos2iGetX = findGetter(pos.getClass(), "getX");
        if (pos2iGetY == null) pos2iGetY = findGetter(pos.getClass(), "getY");
        if (pos2iGetX == null || pos2iGetY == null) return null;
        int x = (Integer) pos2iGetX.invoke(pos);
        int y = (Integer) pos2iGetY.invoke(pos);
        return new int[] { x, y };
    }

    private void ensureBlockPosMethods(Class<?> cls) {
        if (blockPosGetX == null) blockPosGetX = findGetter(cls, "getX");
        if (blockPosGetY == null) blockPosGetY = findGetter(cls, "getY");
        if (blockPosGetZ == null) blockPosGetZ = findGetter(cls, "getZ");
    }

    private void resolve() {
        ClassLoader[] loaders = new ClassLoader[] {
            GOLAccessor.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            net.minecraft.launchwrapper.Launch.classLoader,
        };
        outer:
        for (String name : MASTER_TE_CLASSES) {
            for (ClassLoader loader : loaders) {
                if (loader == null) continue;
                try {
                    masterTeClass = Class.forName(name, false, loader);
                    LGSolver.LOG.info("GOL: bound to master TE {} via {}",
                        name, loader.getClass().getName());
                    break outer;
                } catch (ClassNotFoundException ignored) {}
            }
        }
        if (masterTeClass == null) {
            LGSolver.LOG.warn("GOL: none of {} resolved; disabled", Arrays.toString(MASTER_TE_CLASSES));
            return;
        }
        teGameField = findField(masterTeClass, "game");
        if (teGameField == null) {
            LGSolver.LOG.warn("GOL: no `game` field on master TE; disabling.");
            masterTeClass = null;
            return;
        }
        // Stage is on BoardLootGame (private field named "stage"), walk supers.
        gameStageField = findField(teGameField.getType(), "stage");
        if (gameStageField == null) {
            LGSolver.LOG.warn("GOL: no `stage` field walking game class hierarchy; disabling.");
            masterTeClass = null;
        }
    }

    public void rebind() {
        masterTeClass = null;
        teGameField = gameStageField = stageSequenceField = null;
        knownStageClass = null;
        getBoardOriginMethod = null;
        blockPosGetX = blockPosGetY = blockPosGetZ = null;
        symbolGetPos = null;
        pos2iGetX = pos2iGetY = null;
        warnedAbout = null;
        resolve();
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Method findGetter(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private void warnOnce(String msg, Throwable t) {
        if (warnedAbout != null && warnedAbout.equals(msg)) return;
        warnedAbout = msg;
        LGSolver.LOG.warn("GOL reflection warning: {}", msg, t);
    }
}
