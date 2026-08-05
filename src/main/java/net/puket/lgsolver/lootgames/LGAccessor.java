package net.puket.lgsolver.lootgames;

import net.puket.lgsolver.LGSolver;
import net.puket.lgsolver.solver.Board;
import net.puket.lgsolver.solver.CellState;
import net.minecraft.tileentity.TileEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Reads a Minesweeper snapshot from a LootGames master TileEntity via
 * reflection, so this mod stays loadable when LootGames is absent.
 *
 * <p>Traversal:
 * <pre>
 * MSMasterTile (game) → GameMineSweeper (board) → MSBoard (board[][])
 *   MSField { Type type; Mark mark; boolean isHidden; }
 * </pre>
 *
 * <p>The eager pass at construction resolves the master TE class and the
 * {@code game} field only; the game field is declared as {@code T} on the
 * generic supertype so its erasure is the abstract {@code LootGame}, which
 * lacks a {@code board} holder. Everything downstream of {@code game} is
 * resolved lazily from the first live instance's runtime class.
 */
public final class LGAccessor {

    private Class<?> masterTeClass;
    private Field teGameField;

    private boolean gameFieldsResolved;
    private Class<?> gameClass;
    private Field gameBoardHolderField;
    private Field boardArrayField;
    private Field boardSizeField;
    private Field boardMinesField;
    private Field cellTypeField;
    private Field cellHiddenField;
    private Field cellMarkField;

    private Method getBoardOriginMethod;
    private Method blockPosGetX, blockPosGetY, blockPosGetZ;

    private String warnedAbout;

    /** Snapshot returned by {@link #tryReadSnapshot}. */
    public static final class Snapshot {
        public final Board board;
        /** Origin = world coords of playable cell (0,0). May differ from master TE pos. */
        public final int originX, originY, originZ;
        public Snapshot(Board b, int ox, int oy, int oz) {
            this.board = b;
            this.originX = ox; this.originY = oy; this.originZ = oz;
        }
    }

    public LGAccessor() {
        resolveTeClass();
    }

    public boolean isLootGamesPresent() {
        return masterTeClass != null;
    }

    public boolean isMinesweeperMaster(TileEntity te) {
        return te != null && masterTeClass != null && masterTeClass.isInstance(te);
    }

    public String describe() {
        if (masterTeClass == null) return "not bound";
        return "class=" + masterTeClass.getName()
            + ", gameFieldsResolved=" + gameFieldsResolved
            + (gameFieldsResolved
                ? (", gameClass=" + (gameClass == null ? "?" : gameClass.getName())
                   + ", boardHolder=" + fname(gameBoardHolderField)
                   + ", boardArr=" + fname(boardArrayField)
                   + ", size=" + fname(boardSizeField)
                   + ", mines=" + fname(boardMinesField))
                : "");
    }

    public Snapshot tryReadSnapshot(TileEntity te) {
        if (!isMinesweeperMaster(te)) return null;
        try {
            Object game = teGameField.get(te);
            if (game == null) return null;
            if (!gameFieldsResolved) {
                resolveGameFields(game.getClass());
                if (!gameFieldsResolved) return null;
            }
            Object msBoard = gameBoardHolderField.get(game);
            if (msBoard == null) return null;
            // resolveGameFields proved this is a 2D reference array, so index it
            // directly — 3.6x cheaper than reflective Array.get, and this runs
            // every tick.
            Object rawArr = boardArrayField.get(msBoard);
            if (!(rawArr instanceof Object[])) return null;
            Object[] columns = (Object[]) rawArr;
            int size = boardSizeField.getInt(msBoard);
            if (size <= 0) size = columns.length;
            if (size <= 0) return null;
            int mines = boardMinesField != null ? boardMinesField.getInt(msBoard) : 0;

            CellState[] states = new CellState[size * size];
            byte[] numbers = new byte[size * size];
            Arrays.fill(numbers, (byte) -1);
            // LG stores as board[x][z] (per MSBoard.getField(Pos2i){board[pos.getX()][pos.getY()]}).
            int xMax = Math.min(size, columns.length);
            for (int x = 0; x < xMax; x++) {
                if (!(columns[x] instanceof Object[])) continue;
                Object[] col = (Object[]) columns[x];
                int zMax = Math.min(size, col.length);
                for (int z = 0; z < zMax; z++) {
                    int idx = z * size + x;
                    states[idx] = decodeCell(col[z], numbers, idx);
                }
            }
            for (int i = 0; i < states.length; i++) if (states[i] == null) states[i] = CellState.HIDDEN;
            Board board = new Board(size, mines, states, numbers);

            int ox = te.xCoord, oy = te.yCoord, oz = te.zCoord;
            if (getBoardOriginMethod != null) {
                Object origin = getBoardOriginMethod.invoke(game);
                if (origin != null) {
                    if (blockPosGetX == null) {
                        Class<?> bpCls = origin.getClass();
                        blockPosGetX = findGetter(bpCls, "getX");
                        blockPosGetY = findGetter(bpCls, "getY");
                        blockPosGetZ = findGetter(bpCls, "getZ");
                    }
                    if (blockPosGetX != null) {
                        ox = (Integer) blockPosGetX.invoke(origin);
                        oy = (Integer) blockPosGetY.invoke(origin);
                        oz = (Integer) blockPosGetZ.invoke(origin);
                    }
                }
            }
            return new Snapshot(board, ox, oy, oz);
        } catch (Throwable t) {
            warnOnce("tryReadSnapshot: " + t, t);
            return null;
        }
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

    public void rebind() {
        masterTeClass = null;
        teGameField = null;
        gameFieldsResolved = false;
        gameClass = null;
        gameBoardHolderField = null;
        boardArrayField = boardSizeField = boardMinesField = null;
        cellTypeField = cellHiddenField = cellMarkField = null;
        getBoardOriginMethod = null;
        blockPosGetX = blockPosGetY = blockPosGetZ = null;
        warnedAbout = null;
        resolveTeClass();
    }

    // ------------------------------------------------------------------------

    private void resolveTeClass() {
        ClassLoader[] loaders = new ClassLoader[] {
            LGAccessor.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            net.minecraft.launchwrapper.Launch.classLoader,
        };
        outer:
        for (String name : LGSignatures.KNOWN_TE_CLASSES) {
            for (ClassLoader loader : loaders) {
                if (loader == null) continue;
                try {
                    masterTeClass = Class.forName(name, false, loader);
                    LGSolver.LOG.info("LGSolver: bound to master TE class {} via {}",
                        name, loader.getClass().getName());
                    break outer;
                } catch (ClassNotFoundException e) {
                    LGSolver.LOG.debug("LGSolver: {} not visible to {}: {}",
                        name, loader.getClass().getName(), e.getMessage());
                }
            }
        }
        if (masterTeClass == null) {
            LGSolver.LOG.warn("LGSolver: none of {} resolved via any classloader",
                Arrays.toString(LGSignatures.KNOWN_TE_CLASSES));
            return;
        }

        teGameField = firstDeclaredField(masterTeClass, LGSignatures.KNOWN_GAME_FIELDS);
        if (teGameField == null) {
            LGSolver.LOG.warn("LGSolver: no `game` field on master TE; disabling.");
            masterTeClass = null;
        }
    }

    private void resolveGameFields(Class<?> concreteGameClass) {
        gameClass = concreteGameClass;
        gameBoardHolderField = firstDeclaredField(concreteGameClass, LGSignatures.KNOWN_BOARD_HOLDER_FIELDS);
        if (gameBoardHolderField == null) {
            LGSolver.LOG.warn("LGSolver: no board holder on runtime game class {}; disabling.", concreteGameClass);
            return;
        }

        Class<?> boardClass = gameBoardHolderField.getType();
        boardArrayField = firstDeclaredField(boardClass, LGSignatures.KNOWN_BOARD_ARRAY_FIELDS);
        boardSizeField  = firstDeclaredField(boardClass, LGSignatures.KNOWN_SIZE_FIELDS);
        boardMinesField = firstDeclaredField(boardClass, LGSignatures.KNOWN_MINES_FIELDS);
        if (boardArrayField == null || boardSizeField == null) {
            LGSolver.LOG.warn("LGSolver: MSBoard missing array/size on {}; disabling.", boardClass);
            return;
        }

        Class<?> arrType = boardArrayField.getType();
        Class<?> cellClass = null;
        if (arrType.isArray()) {
            Class<?> inner = arrType.getComponentType();
            cellClass = inner.isArray() ? inner.getComponentType() : inner;
        }
        if (cellClass == null || cellClass.isPrimitive()) {
            LGSolver.LOG.warn("LGSolver: unsupported MSBoard array type {}; disabling.", arrType);
            return;
        }
        cellTypeField   = firstDeclaredField(cellClass, LGSignatures.KNOWN_CELL_TYPE_FIELDS);
        cellHiddenField = firstDeclaredField(cellClass, LGSignatures.KNOWN_CELL_HIDDEN_FIELDS);
        cellMarkField   = firstDeclaredField(cellClass, LGSignatures.KNOWN_CELL_MARK_FIELDS);
        if (cellTypeField == null || cellHiddenField == null) {
            LGSolver.LOG.warn("LGSolver: MSField layout not recognized on {}; disabling.", cellClass);
            return;
        }
        getBoardOriginMethod = findGetter(concreteGameClass, "getBoardOrigin");
        gameFieldsResolved = true;
        LGSolver.LOG.info("LGSolver: game fields resolved from {} — {}",
            concreteGameClass.getName(), describe());
    }

    private static Field firstDeclaredField(Class<?> cls, String[] names) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
        }
        return null;
    }

    private static String fname(Field f) { return f == null ? "?" : f.getName(); }

    private void warnOnce(String msg, Throwable t) {
        if (warnedAbout != null && warnedAbout.equals(msg)) return;
        warnedAbout = msg;
        LGSolver.LOG.warn("LGSolver reflection warning: {}", msg, t);
    }

    // ------------------------------------------------------------------------

    private CellState decodeCell(Object cell, byte[] numberOut, int idx) throws IllegalAccessException {
        if (cell == null) return CellState.HIDDEN;
        boolean hidden = cellHiddenField.getBoolean(cell);
        if (cellMarkField != null) {
            Object mark = cellMarkField.get(cell);
            if (mark instanceof Enum<?>) {
                String mn = ((Enum<?>) mark).name();
                if (mn.equals("FLAG")) return CellState.FLAGGED;
                if (mn.equals("QUESTION_MARK") || mn.equals("QUESTION")) return CellState.QUESTION;
            }
        }
        if (hidden) return CellState.HIDDEN;
        Object type = cellTypeField.get(cell);
        if (!(type instanceof Enum<?>)) return CellState.REVEALED_EMPTY;
        String tn = ((Enum<?>) type).name();
        switch (tn) {
            case "BOMB": case "MINE": return CellState.REVEALED_MINE;
            case "EMPTY":              return CellState.REVEALED_EMPTY;
            case "ONE":   numberOut[idx] = 1; return CellState.REVEALED_NUMBER;
            case "TWO":   numberOut[idx] = 2; return CellState.REVEALED_NUMBER;
            case "THREE": numberOut[idx] = 3; return CellState.REVEALED_NUMBER;
            case "FOUR":  numberOut[idx] = 4; return CellState.REVEALED_NUMBER;
            case "FIVE":  numberOut[idx] = 5; return CellState.REVEALED_NUMBER;
            case "SIX":   numberOut[idx] = 6; return CellState.REVEALED_NUMBER;
            case "SEVEN": numberOut[idx] = 7; return CellState.REVEALED_NUMBER;
            case "EIGHT": numberOut[idx] = 8; return CellState.REVEALED_NUMBER;
            default:                   return CellState.REVEALED_EMPTY;
        }
    }
}
