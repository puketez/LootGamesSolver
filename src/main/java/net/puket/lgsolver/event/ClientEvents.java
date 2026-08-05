package net.puket.lgsolver.event;

import net.puket.lgsolver.LGSolver;
import net.puket.lgsolver.client.EspRenderer;
import net.puket.lgsolver.client.HintRenderer;
import net.puket.lgsolver.gol.GOLAccessor;
import net.puket.lgsolver.gol.GOLRenderer;
import net.puket.lgsolver.gol.GOLState;
import net.puket.lgsolver.solver.AsyncSolver;
import net.puket.lgsolver.solver.Board;
import net.puket.lgsolver.solver.Hint;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClientEvents {

    private static final int SCAN_EVERY_TICKS = 10;
    /** Only bind (and solve) a game whose master is within this range of the player. */
    private static final double BIND_RADIUS_SQ = 40.0 * 40.0;

    private final AsyncSolver asyncSolver = new AsyncSolver();
    private final HintRenderer renderer = new HintRenderer();
    private final GOLRenderer golRenderer = new GOLRenderer();
    private final EspRenderer espRenderer = new EspRenderer();
    private final GOLState golState = new GOLState();

    private BoundBoard currentBoard;
    /** The solve result {@link #currentBoard} was built from, to skip rebuilds. */
    private AsyncSolver.Result lastRendered;
    private int golTx, golTy, golTz;
    private boolean golBound;
    private int tickCounter = 0;
    private boolean hintsKeyHeld, espKeyHeld;

    /** Cached ESP targets, each {@code int[]{x,y,z,kind}}; refreshed on a tick throttle. */
    private volatile List<int[]> espTargets = Collections.emptyList();

    /** Chat prefix for every player-facing message. */
    private static final String PREFIX = "§3[LGSolver]§r ";

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(PREFIX + msg));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;

        boolean wantHints = LGSolver.config.renderHints;
        boolean wantEsp = LGSolver.config.renderEsp;

        // Both off → no per-tick work. The Simon Says sequence is kept anyway
        // (see pollGOL below); it is a few ints and holds no MC references.
        if (!wantHints && !wantEsp) {
            if (currentBoard != null) unbindMS();
            if (!espTargets.isEmpty()) espTargets = Collections.emptyList();
            return;
        }

        tickCounter++;

        if (wantHints) {
            // Cheap per-tick refresh of an already-bound game (single getTileEntity).
            if (currentBoard != null) {
                TileEntity te = mc.theWorld.getTileEntity(currentBoard.tx, currentBoard.ty, currentBoard.tz);
                if (te != null && LGSolver.lgAccessor.isMinesweeperMaster(te)) refreshMS(te);
                else unbindMS();
            }
        } else {
            // Safe to drop: the board re-reads from the tile in full.
            if (currentBoard != null) unbindMS();
        }

        // Poll even while hidden. The Simon Says sequence reaches the client
        // only during the show phase, so a skipped poll loses the round.
        if (golBound) pollGOL(mc);

        // One periodic pass over loaded tile entities serves ESP and (re)binding.
        if (tickCounter % SCAN_EVERY_TICKS == 0) {
            scanMasters(mc, wantHints, wantEsp);
        }
        if (!wantEsp && !espTargets.isEmpty()) espTargets = Collections.emptyList();
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        // Press edge only — KeyInputEvent repeats while a key is held, which
        // otherwise flips the toggle ~20x/second.
        boolean toggled = false;

        boolean hints = LGSolver.config.toggleKey.getIsKeyPressed();
        if (hints && !hintsKeyHeld) {
            LGSolver.config.renderHints = !LGSolver.config.renderHints;
            chat("Solver hints " + (LGSolver.config.renderHints ? "§aon" : "§coff"));
            toggled = true;
        }
        hintsKeyHeld = hints;

        boolean esp = LGSolver.config.espKey.getIsKeyPressed();
        if (esp && !espKeyHeld) {
            LGSolver.config.renderEsp = !LGSolver.config.renderEsp;
            chat("Puzzle tracer " + (LGSolver.config.renderEsp ? "§aon" : "§coff"));
            toggled = true;
        }
        espKeyHeld = esp;

        if (toggled) LGSolver.config.save();
    }

    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.entityPlayer == null || event.world == null || !event.world.isRemote) return;
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        if (!golBound || !"waiting_for_sequence".equals(golState.stageId)) return;

        // Board plane only: without this, any right-click sharing the board's
        // x/z column counted as a board click.
        if (event.y != golState.originY) return;

        int gx = event.x - golState.originX;
        int gz = event.z - golState.originZ;
        if (gx < 0 || gz < 0 || gx >= golState.size || gz >= golState.size) return;
        if (gx == 1 && gz == 1) return;

        int[] next = golState.nextExpected();
        if (next == null) return;
        if (gx == next[0] && gz == next[1]) {
            golState.progress++;
        } else {
            // Advise, never block — a wrong click stays the player's to make.
            chat("§cWrong cell. §rNext in sequence is (" + next[0] + ", " + next[1]
                + ") — you clicked (" + gx + ", " + gz + ").");
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (LGSolver.config.renderEsp) espRenderer.render(event.partialTicks, espTargets);
        if (!LGSolver.config.renderHints) return;
        if (currentBoard != null) renderer.render(event.partialTicks, currentBoard);
        if (golBound && golState.hasSequence()) golRenderer.render(event.partialTicks, golState);
    }

    // ---- Unified master scan ------------------------------------------------

    /**
     * Single pass over {@code loadedTileEntityList} that (a) collects every
     * puzzle/MS/GOL master for the ESP cache, and (b) binds the nearest MS and
     * GOL master within {@link #BIND_RADIUS_SQ} for the hint overlays. This
     * replaces the old per-block {@code getTileEntity} triple loops (~16.8k
     * lookups/interval each) with one ~O(loaded-TEs) walk.
     */
    private void scanMasters(Minecraft mc, boolean wantHints, boolean wantEsp) {
        World world = mc.theWorld;
        List<?> tiles = world.loadedTileEntityList;
        if (tiles == null) {
            if (wantEsp) espTargets = Collections.emptyList();
            return;
        }
        EntityClientPlayerMP p = mc.thePlayer;
        double px = p.posX, py = p.posY, pz = p.posZ;

        List<int[]> found = wantEsp ? new ArrayList<int[]>() : null;
        boolean needMS = wantHints && currentBoard == null;
        boolean needGOL = wantHints && !golBound;
        double bestMS = BIND_RADIUS_SQ, bestGOL = BIND_RADIUS_SQ;
        TileEntity bestMSte = null, bestGOLte = null;

        // Indexed walk, no copy. This list is mutated only by the client's own
        // tick and we run outside it; re-reading size() means a concurrent
        // shrink ends the scan early instead of throwing.
        for (int i = 0; i < tiles.size(); i++) {
            Object o = tiles.get(i);
            if (!(o instanceof TileEntity)) continue;
            TileEntity te = (TileEntity) o;
            if (te.isInvalid()) continue;
            int kind;
            if (LGSolver.lgAccessor.isMinesweeperMaster(te)) kind = EspRenderer.KIND_MS;
            else if (LGSolver.golAccessor.isMaster(te)) kind = EspRenderer.KIND_GOL;
            else if (LGSolver.puzzleAccessor.isPuzzleMaster(te)) kind = EspRenderer.KIND_PUZZLE;
            else continue;

            if (found != null) found.add(new int[] { te.xCoord, te.yCoord, te.zCoord, kind });

            if (kind == EspRenderer.KIND_MS && needMS) {
                double d = distSq(te, px, py, pz);
                if (d < bestMS) { bestMS = d; bestMSte = te; }
            } else if (kind == EspRenderer.KIND_GOL && needGOL) {
                double d = distSq(te, px, py, pz);
                if (d < bestGOL) { bestGOL = d; bestGOLte = te; }
            }
        }

        if (wantEsp) espTargets = found;
        if (needMS && bestMSte != null) currentBoard = snapshotMS(bestMSte);
        if (needGOL && bestGOLte != null) {
            golTx = bestGOLte.xCoord; golTy = bestGOLte.yCoord; golTz = bestGOLte.zCoord;
            golBound = true;
            pollGOL(mc);
        }
    }

    private static double distSq(TileEntity te, double px, double py, double pz) {
        double dx = (te.xCoord + 0.5) - px;
        double dy = (te.yCoord + 0.5) - py;
        double dz = (te.zCoord + 0.5) - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- Minesweeper --------------------------------------------------------

    private BoundBoard snapshotMS(TileEntity te) {
        net.puket.lgsolver.lootgames.LGAccessor.Snapshot snap = LGSolver.lgAccessor.tryReadSnapshot(te);
        if (snap == null) return null;
        asyncSolver.submit(snap.board);
        // Bind now, draw hints once the first solve lands (next tick at worst).
        return new BoundBoard(te.xCoord, te.yCoord, te.zCoord,
            snap.originX, snap.originY, snap.originZ, snap.board, Collections.<Hint>emptyList());
    }

    /**
     * Re-read the bound board and hand it to the solver thread, which ignores
     * it unless it changed. Reading is cheap; solving is not, so we only pick
     * up whatever result is already finished.
     */
    private void refreshMS(TileEntity te) {
        net.puket.lgsolver.lootgames.LGAccessor.Snapshot snap = LGSolver.lgAccessor.tryReadSnapshot(te);
        if (snap == null) { unbindMS(); return; }
        asyncSolver.submit(snap.board);

        AsyncSolver.Result r = asyncSolver.latest();
        if (r == null) return; // first solve still in flight
        if (r == lastRendered && currentBoard != null
            && currentBoard.originX == snap.originX
            && currentBoard.originY == snap.originY
            && currentBoard.originZ == snap.originZ) {
            return; // nothing new to draw
        }
        lastRendered = r;
        currentBoard = new BoundBoard(te.xCoord, te.yCoord, te.zCoord,
            snap.originX, snap.originY, snap.originZ, r.board, r.hints);
    }

    /** Release the Minesweeper binding and everything the solver was holding. */
    private void unbindMS() {
        currentBoard = null;
        lastRendered = null;
        asyncSolver.reset();
    }

    // ---- ESP / tracer -------------------------------------------------------

    /** Live ESP diagnostic for {@code /lgsolver dump}. Does a fresh scan. */
    public String espDebug() {
        Minecraft mc = Minecraft.getMinecraft();
        StringBuilder sb = new StringBuilder("[LGSolver-ESP] renderEsp=").append(LGSolver.config.renderEsp)
            .append(" cached=").append(espTargets.size())
            .append(" | MS bound=").append(LGSolver.lgAccessor.isLootGamesPresent())
            .append(" GOL present=").append(LGSolver.golAccessor.isPresent())
            .append(" Puzzle present=").append(LGSolver.puzzleAccessor.isPresent());
        if (mc.theWorld == null) return sb.append(" | world=null").toString();
        Object[] arr = mc.theWorld.loadedTileEntityList.toArray();
        int total = arr.length, ms = 0, gol = 0, puz = 0;
        for (Object o : arr) {
            if (!(o instanceof TileEntity)) continue;
            TileEntity te = (TileEntity) o;
            if (LGSolver.lgAccessor.isMinesweeperMaster(te)) {
                ms++;
                sb.append("\n  MS  @ ").append(te.xCoord).append(",").append(te.yCoord).append(",").append(te.zCoord)
                  .append(" class=").append(te.getClass().getName());
            } else if (LGSolver.golAccessor.isMaster(te)) {
                gol++;
                sb.append("\n  GOL @ ").append(te.xCoord).append(",").append(te.yCoord).append(",").append(te.zCoord)
                  .append(" class=").append(te.getClass().getName());
            } else if (LGSolver.puzzleAccessor.isPuzzleMaster(te)) {
                puz++;
                sb.append("\n  PUZ @ ").append(te.xCoord).append(",").append(te.yCoord).append(",").append(te.zCoord)
                  .append(" class=").append(te.getClass().getName());
            }
        }
        sb.insert(sb.indexOf(" | MS bound"), " loadedTEs=" + total + " ms=" + ms + " gol=" + gol + " puz=" + puz);
        return sb.toString();
    }

    // ---- Game of Light ------------------------------------------------------

    private void pollGOL(Minecraft mc) {
        TileEntity te = mc.theWorld.getTileEntity(golTx, golTy, golTz);
        if (te == null || !LGSolver.golAccessor.isMaster(te)) {
            golBound = false;
            golState.clear();
            return;
        }
        GOLAccessor.Snapshot snap = LGSolver.golAccessor.tryReadSnapshot(te);
        if (snap == null) return;
        if ("show_sequence".equals(snap.stageId)) {
            golState.applyFromShow(snap);
        } else if ("waiting_for_sequence".equals(snap.stageId)) {
            golState.applyFromWaiting(snap);
        } else {
            golState.originX = snap.originX;
            golState.originY = snap.originY;
            golState.originZ = snap.originZ;
            golState.stageId = snap.stageId;
        }
    }

    public GOLState golState() { return golState; }
    public boolean golBound() { return golBound; }

    public static final class BoundBoard {
        public final int tx, ty, tz;
        public final int originX, originY, originZ;
        public final Board board;
        public final List<Hint> hints;
        public BoundBoard(int tx, int ty, int tz, int ox, int oy, int oz,
                          Board board, List<Hint> hints) {
            this.tx = tx; this.ty = ty; this.tz = tz;
            this.originX = ox; this.originY = oy; this.originZ = oz;
            this.board = board;
            this.hints = hints == null ? Collections.emptyList() : hints;
        }
    }

    public BoundBoard current() { return currentBoard; }
}
