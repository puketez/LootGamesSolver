package net.puket.lgsolver.lootgames;

import net.puket.lgsolver.LGSolver;
import net.minecraft.tileentity.TileEntity;

/**
 * Detects the un-activated LootGames puzzle master block. A freshly generated
 * dungeon places a {@code PuzzleMasterBlock} whose TileEntity is
 * {@code PuzzleMasterTile} — the animated block the player hunts for. Only
 * after it is clicked does LootGames pick a game and swap in an
 * {@code MSMasterTile} / {@code GOLMasterTile} (a different class hierarchy,
 * handled by {@link LGAccessor} / {@code GOLAccessor}). This accessor covers
 * the pre-click state so the ESP can point at a puzzle you haven't started.
 */
public final class PuzzleAccessor {

    private static final String[] MASTER_TE_CLASSES = new String[] {
        "ru.timeconqueror.lootgames.common.block.tile.PuzzleMasterTile",
    };

    private Class<?> masterTeClass;

    public PuzzleAccessor() { resolve(); }

    public boolean isPresent() { return masterTeClass != null; }

    public boolean isPuzzleMaster(TileEntity te) {
        return te != null && masterTeClass != null && masterTeClass.isInstance(te);
    }

    public String describe() {
        return masterTeClass == null ? "Puzzle: not bound"
            : "Puzzle: class=" + masterTeClass.getName();
    }

    public void rebind() {
        masterTeClass = null;
        resolve();
    }

    private void resolve() {
        ClassLoader[] loaders = new ClassLoader[] {
            PuzzleAccessor.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            net.minecraft.launchwrapper.Launch.classLoader,
        };
        outer:
        for (String name : MASTER_TE_CLASSES) {
            for (ClassLoader loader : loaders) {
                if (loader == null) continue;
                try {
                    masterTeClass = Class.forName(name, false, loader);
                    LGSolver.LOG.info("Puzzle: bound to master TE {} via {}",
                        name, loader.getClass().getName());
                    break outer;
                } catch (ClassNotFoundException ignored) {}
            }
        }
        if (masterTeClass == null) {
            LGSolver.LOG.warn("Puzzle: PuzzleMasterTile not resolved; puzzle ESP disabled");
        }
    }
}
