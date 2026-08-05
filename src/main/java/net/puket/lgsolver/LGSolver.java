package net.puket.lgsolver;

import net.puket.lgsolver.command.LGSolverCommand;
import net.puket.lgsolver.event.ClientEvents;
import net.puket.lgsolver.gol.GOLAccessor;
import net.puket.lgsolver.lootgames.LGAccessor;
import net.puket.lgsolver.lootgames.PuzzleAccessor;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = LGSolver.MODID, name = LGSolver.NAME, version = LGSolver.VERSION,
     acceptableRemoteVersions = "*",
     dependencies = "after:lootgames")
public class LGSolver {
    public static final String MODID = "lgsolver";
    public static final String NAME = "LGSolver";
    public static final String VERSION = "1.0.0";

    public static final Logger LOG = LogManager.getLogger(MODID);

    public static LGSolverConfig config;
    public static LGAccessor lgAccessor;
    public static GOLAccessor golAccessor;
    public static PuzzleAccessor puzzleAccessor;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // Forge 1.7.10's @Mod has no clientSideOnly, so gate the side here.
        // Everything below reaches KeyBinding/ClientRegistry, which a dedicated
        // server does not have; returning early never loads them.
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            LOG.info("LGSolver is a client-side mod; staying dormant on the server.");
            return;
        }
        Configuration cfg = new Configuration(event.getSuggestedConfigurationFile());
        config = new LGSolverConfig(cfg);
        cfg.save();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (config == null) return; // not the client side — preInit bailed
        lgAccessor = new LGAccessor();
        golAccessor = new GOLAccessor();
        puzzleAccessor = new PuzzleAccessor();
        ClientEvents client = new ClientEvents();
        ClientCommandHandler.instance.registerCommand(new LGSolverCommand(client));

        if (!lgAccessor.isLootGamesPresent() && !golAccessor.isPresent() && !puzzleAccessor.isPresent()) {
            LOG.warn("LootGames not detected; LGSolver disabled. Use /lgsolver rebind after installing.");
            return;
        }
        LOG.info("Binding to LootGames — MS: {} | GOL: {}", lgAccessor.describe(), golAccessor.describe());

        MinecraftForge.EVENT_BUS.register(client);
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(client);
    }
}