package com.vutocorp.livevillage;

import org.bukkit.Material;

/**
 * Parametros del motor. Calco de Cfg.java del mod: mismos valores, Block/Blocks
 * de NeoForge cambiados por Material de Bukkit. El resto (Structures, VillageEngine,
 * PathBuilder) llega en la Fase 2; estas constantes ya se dejan listas para entonces.
 */
public final class Cfg {
    private Cfg() {}

    // ----- Estructuras -----
    public static final String DEFAULT_SKIN = "japon";
    public static final Material MARK_VILLAGER = Material.GOLD_BLOCK;
    public static final Material MARK_JOB      = Material.EMERALD_BLOCK;
    public static final Material MARK_SIGN     = Material.LAPIS_BLOCK;
    public static final boolean EMPTY_CONTAINERS = true;

    // ----- Aldeano y cartel -----
    public static final Material SIGN_BLOCK = Material.BIRCH_SIGN;
    public static final boolean SIGN_GLOWING = true;
    public static final int VILLAGER_LEVEL = 2;
    public static final boolean VILLAGER_NAME_VISIBLE = true;

    // ----- Chunks forzados -----
    public static final int FORCE_MARGIN = 16;
    public static final int MAX_FORCED_CHUNKS = 400;
    public static final int MAX_BUILD_CHUNKS = 64;
    public static final int BUILD_RADIUS = 24;
    public static final int CHUNK_HOLD_TICKS = 60;

    // ----- Pausa de IA -----
    public static final int AI_PAUSE_MIN_HOUSES = 40;
    public static final int AI_ACTIVE_RADIUS = 48;
    public static final int AI_CHECK_INTERVAL = 40;

    // ----- Ritmo de donaciones -----
    public static final long DONACION_COOLDOWN_MS = 1500;
    public static final int DONACION_TOPE_MINUTO = 40;
    public static final int DONACION_TOPE_PUEBLO = 0;
    public static final boolean PATCH_BASE_HOLES = true;
    public static final String WAYSTONE_ID = "waystones:waystone";
    public static final Material PLAZA_FALLBACK = Material.BELL;

    // ----- Decoracion de los bordes del camino -----
    public static final boolean DECORAR_CAMINOS = true;
    public static final int DECOR_CADA = 5;
    public static final Material[] DECOR_FLORES = {
        Material.POPPY, Material.DANDELION, Material.AZURE_BLUET, Material.OXEYE_DAISY,
        Material.CORNFLOWER, Material.PINK_TULIP, Material.RED_TULIP, Material.WHITE_TULIP,
        Material.SHORT_GRASS, Material.SHORT_GRASS, Material.FERN
    };

    // ----- Dimensiones de la casa provisional -----
    public static final int PLOT_HALF = 2;
    public static final int WALL_HEIGHT = 3;
    public static final int CLEAR_HEIGHT = 6;
    public static final int PLAZA_HALF = 4;
    public static final int HOUSE_LEVEL_MAX_RAISE = 2;
    public static final int HOUSE_WIDTH = 2 * PLOT_HALF + 1;

    // ----- Colocacion (scatter) -----
    public static final int HOUSE_GAP = 5;
    public static final int MIN_DISTANCE = HOUSE_WIDTH + HOUSE_GAP;
    public static final int INITIAL_RADIUS = 14;
    public static final int RADIUS_STEP = 5;
    public static final int MAX_TRIES = 500;

    // ----- Camino -----
    public static final int PATH_HALF = 1;
    public static final int PATH_WIDTH = 2;
    public static final int PATH_MARGIN = 1;
    public static final int ASTAR_MAX_NODES = 60000;
    public static final int COST_STEP = 10;
    public static final int COST_PATH_REUSE = 1;
    public static final int COST_SLOPE = 8;
    public static final int COST_PARALLEL = 6;
    public static final int COST_TURN = 40;
    public static final int STRAIGHTEN_SLOPE_TOL = 6;
    public static final int TRUNK_LEN = 18;
    public static final int PATH_SKIP_DIST = 3;
    public static final int PATH_SMOOTH = 3;
    public static final Material PATH_SUPPORT = Material.DEEPSLATE_TILES;
    public static final Material PATH_SUPPORT_DEEP = Material.DARK_OAK_FENCE;
    public static final int SHALLOW_FILL = 2;

    // ----- Faroles -----
    public static final int LAMP_SPACING = 10;
    public static final int LAMP_MIN_GAP = 7;
    public static final int LAMP_SIDE_DIST = 2;
    public static final int LAMP_POLE_HEIGHT = 4;
    public static final Material LAMP_BASE = Material.DEEPSLATE_BRICKS;
    public static final Material LAMP_POLE = Material.DARK_OAK_FENCE;
    public static final Material LAMP_ARM  = Material.DEEPSLATE_TILE_SLAB;

    // ----- Arboles del camino -----
    public static final boolean PLANTAR_ARBOLES = true;
    public static final int TREE_SPACING  = 23;
    public static final int TREE_MIN_GAP  = 12;
    public static final int TREE_LAMP_GAP = 6;
    public static final int TREE_SIDE_DIST = 2;

    // ----- Mascotas de donador -----
    public static final int MOBS_POR_CASA = 4;
    public static final boolean MOB_NOMBRE_VISIBLE = true;
    public static final boolean MOB_INVULNERABLE = true;
    public static final int MOB_RADIO_BUSQUEDA = 6;

    // ----- Terreno -----
    public static final int MAX_FILL_DOWN = 16;

    // ----- Bloques -----
    public static final Material PATH_BLOCK  = Material.DEEPSLATE_TILES;
    public static final Material FLOOR_BLOCK = Material.STONE_BRICKS;
    public static final Material WALL_BLOCK  = Material.OAK_PLANKS;
    public static final Material ROOF_BLOCK  = Material.OAK_PLANKS;
    public static final Material PLAZA_BLOCK = Material.POLISHED_ANDESITE;
    public static final Material FILL_BLOCK  = Material.DIRT;
}
