package net.puket.lgsolver;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.config.Configuration;
import org.lwjgl.input.Keyboard;

public class LGSolverConfig {
    public boolean renderHints;
    public boolean showProbabilityLabels;
    public boolean debugLogging;
    public boolean renderEsp;

    public final KeyBinding toggleKey;
    public final KeyBinding espKey;

    private final Configuration cfg;

    public LGSolverConfig(Configuration cfg) {
        this.cfg = cfg;
        cfg.load();
        renderHints = cfg.getBoolean(
            "renderHints", "general", true,
            "Render solver hints as coloured overlays on the LootGames Minesweeper board.");
        showProbabilityLabels = cfg.getBoolean(
            "showProbabilityLabels", "general", true,
            "Show the mine-probability percentage for cells that are not guaranteed safe or guaranteed mines.");
        debugLogging = cfg.getBoolean(
            "debugLogging", "general", false,
            "Verbose logging around board detection and reflection binding.");
        renderEsp = cfg.getBoolean(
            "renderEsp", "general", true,
            "Draw a through-wall tracer/ESP box on every LootGames puzzle master block within render distance.");

        toggleKey = new KeyBinding("key.lgsolver.toggle", Keyboard.KEY_V, "key.categories.lgsolver");
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(toggleKey);
        espKey = new KeyBinding("key.lgsolver.esp", Keyboard.KEY_B, "key.categories.lgsolver");
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(espKey);
    }

    /** Persist the in-game toggles so V and B survive a restart. */
    public void save() {
        cfg.get("general", "renderHints", true).set(renderHints);
        cfg.get("general", "renderEsp", true).set(renderEsp);
        if (cfg.hasChanged()) cfg.save();
    }
}
