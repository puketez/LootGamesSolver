package net.puket.lgsolver.lootgames;

/**
 * Runtime-known class and field names for the LootGames Minesweeper master
 * TileEntity. {@link LGAccessor} tries each in order and caches the first
 * match. First entry in each list is the confirmed GTNH 1.7.10 signature
 * from LootGames 2.2.0.1; later entries are legacy/upstream fallbacks.
 */
public final class LGSignatures {
    private LGSignatures() {}

    /** Fully-qualified master TileEntity classes to try. */
    public static final String[] KNOWN_TE_CLASSES = new String[] {
        "ru.timeconqueror.lootgames.common.block.tile.MSMasterTile",
        "ru.timeconqueror.lootgames.minigame.minesweeper.TEMasterMS",
        "ru.timeconqueror.lootgames.minigame.minesweeper.TileEntityMSMaster",
    };

    /** Field names to try (on the master TE, walking supers) for the game object. */
    public static final String[] KNOWN_GAME_FIELDS = new String[] {
        "game",
    };

    /** Field names to try (on the game object) for the MSBoard instance. */
    public static final String[] KNOWN_BOARD_HOLDER_FIELDS = new String[] {
        "board",
    };

    /** Field names to try (on the MSBoard) for the 2D field array. */
    public static final String[] KNOWN_BOARD_ARRAY_FIELDS = new String[] {
        "board",
        "field",
        "cells",
    };

    /** Field names to try (on the MSBoard) for the size scalar. */
    public static final String[] KNOWN_SIZE_FIELDS = new String[] {
        "size",
        "boardSize",
        "fieldSize",
    };

    /** Field names to try (on the MSBoard) for the mine count. */
    public static final String[] KNOWN_MINES_FIELDS = new String[] {
        "bombCount",
        "mines",
        "mineCount",
        "totalMines",
    };

    /** Field names inside an MSField-like cell object. */
    public static final String[] KNOWN_CELL_TYPE_FIELDS = new String[] {
        "type",
    };
    public static final String[] KNOWN_CELL_HIDDEN_FIELDS = new String[] {
        "isHidden",
        "hidden",
    };
    public static final String[] KNOWN_CELL_MARK_FIELDS = new String[] {
        "mark",
        "marker",
    };
}