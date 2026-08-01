package com.vutocorp.livevillage;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Habitante de la casa: aldeano + puesto de trabajo + cartel con el nombre del donador.
 * Port de Villagers.java del mod.
 *
 * La diferencia grande y a favor: en el mod, poner profesion y nivel necesita
 * Compat.aplicarProfesion() porque VillagerData es inmutable y la profesion es un
 * ResourceKey que hay que resolver contra el registro del mundo (todo el lio de la
 * 26.1). En Bukkit, Villager.setProfession(Profession) y setVillagerLevel(int) son
 * setters directos y estables: no hace falta ningun Compat. Por eso este fichero no
 * tiene una clase hermana "VillagerCompat": no le hace falta.
 */
public final class Villagers {

    private Villagers() {}

    /** Mismo orden que en el mod: PROF_IDS <-> Profession de Bukkit <-> bloque de trabajo
     *  <-> etiqueta en español. Las cuatro tablas van alineadas por indice. */
    private static final String[] PROF_IDS = {
        "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher",
        "leatherworker", "librarian", "mason", "shepherd", "toolsmith", "weaponsmith"
    };
    private static final Villager.Profession[] PROFESIONES = {
        Villager.Profession.ARMORER, Villager.Profession.BUTCHER, Villager.Profession.CARTOGRAPHER,
        Villager.Profession.CLERIC, Villager.Profession.FARMER, Villager.Profession.FISHERMAN,
        Villager.Profession.FLETCHER, Villager.Profession.LEATHERWORKER, Villager.Profession.LIBRARIAN,
        Villager.Profession.MASON, Villager.Profession.SHEPHERD, Villager.Profession.TOOLSMITH,
        Villager.Profession.WEAPONSMITH
    };
    private static final Material[] PUESTOS = {
        Material.BLAST_FURNACE, Material.SMOKER, Material.CARTOGRAPHY_TABLE,
        Material.BREWING_STAND, Material.COMPOSTER, Material.BARREL,
        Material.FLETCHING_TABLE, Material.CAULDRON, Material.LECTERN,
        Material.STONECUTTER, Material.LOOM, Material.SMITHING_TABLE,
        Material.GRINDSTONE
    };
    private static final String[] PROF_ES = {
        "Armero", "Carnicero", "Cartografo", "Clerigo", "Granjero", "Pescador", "Flechero",
        "Peletero", "Bibliotecario", "Albanil", "Pastor", "Herrero", "Armero de armas"
    };

    private static int indice(String profId) {
        for (int i = 0; i < PROF_IDS.length; i++) if (PROF_IDS[i].equals(profId)) return i;
        return -1;
    }

    // ---------------- API ----------------

    /** Puesto de trabajo + cartel + aldeano. Se llama justo despues de pegar la casa. */
    public static void populate(World w, House house) {
        // La profesion se sortea UNA sola vez, la primera. populate() tambien lo llama
        // /lv house regenerate, y si re-sorteara siempre, al regenerar la casa cambiaria
        // de oficio y el cartel dejaria de cuadrar con lo que el donador habia visto.
        // La profesion es parte de la identidad de la casa, no de su construccion.
        if (house.profession == null || house.profession.isEmpty()) {
            house.profession = PROF_IDS[new Random().nextInt(PROF_IDS.length)];
        }

        if (house.markJob != null)  placeJob(w, house, house.profession);
        if (house.markSign != null) placeSign(w, house);
        spawn(w, house, house.profession);
    }

    /**
     * Cambia el nombre de la casa: el del aldeano y el del cartel. Existe porque los
     * nombres de TikTok traen caracteres que NameUtil quita, y a veces lo que queda no
     * se parece al original. Devuelve false si el aldeano no estaba cargado (el cartel
     * se cambia igual).
     */
    public static boolean renombrar(World w, House house, String nuevoNombre) {
        house.name = nuevoNombre;
        if (house.markSign != null) {
            org.bukkit.block.BlockState be = w.getBlockAt(house.markSign.x, house.markSign.y, house.markSign.z).getState();
            if (be instanceof Sign s) {
                escribirCartel(s, house);
                s.update();
            } else {
                placeSign(w, house);              // el cartel ya no estaba: se repone
            }
        }
        if (!alive(w, house)) return false;
        Entity e = entity(w, house);
        if (e != null) ponerNombre(e, nuevoNombre, Cfg.VILLAGER_NAME_VISIBLE);
        return true;
    }

    /** Vuelve a poner el aldeano si ya no existe. true si hizo falta reponerlo. */
    public static boolean revive(World w, House house) {
        if (alive(w, house)) return false;
        spawn(w, house, house.profession);
        return true;
    }

    /** true si el aldeano de esta casa sigue existiendo. */
    public static boolean alive(World w, House house) {
        Entity e = entity(w, house);
        return e != null && !e.isDead();
    }

    // ---------------- pausa de IA ----------------

    public static int lastActive = 0, lastPaused = 0;

    /**
     * Congela la IA de los aldeanos que no tienen a nadie cerca. El cerebro de un
     * aldeano es de lo mas caro que tiene Minecraft: sensores, POIs, horarios, cotilleo
     * y pathfinding. Con 200 casas eso hunde el TPS mucho antes que los bloques.
     * setAI(false) lo apaga entero; un aldeano pausado sigue pudiendo comerciar.
     *
     * Solo se pausa por encima de AI_PAUSE_MIN_HOUSES, y siempre se despierta a los que
     * tienen un jugador a menos de AI_ACTIVE_RADIUS.
     */
    public static void tickAiPause(World w, Village v) {
        if (!v.aiPause || v.houses.size() < Cfg.AI_PAUSE_MIN_HOUSES) return;
        double r2 = (double) Cfg.AI_ACTIVE_RADIUS * Cfg.AI_ACTIVE_RADIUS;
        List<Player> players = w.getPlayers();
        for (House h : v.houses) {
            if (h.villagerId == null || h.villagerId.isEmpty()) continue;
            Pos3 at = (h.markVillager != null) ? h.markVillager : new Pos3(h.x, h.y, h.z);
            if (!w.isChunkLoaded(at.x >> 4, at.z >> 4)) continue;
            boolean near = false;
            for (Player p : players) {
                if (p.getWorld() != w) continue;
                double dx = p.getLocation().getX() - (at.x + 0.5), dy = p.getLocation().getY() - at.y,
                       dz = p.getLocation().getZ() - (at.z + 0.5);
                if (dx * dx + dy * dy + dz * dz <= r2) { near = true; break; }
            }
            Entity e = entity(w, h);
            if (!(e instanceof Villager vi)) continue;
            if (vi.hasAI() != near) vi.setAI(near);        // solo tocar si cambia
            if (near) lastActive++; else lastPaused++;
        }
    }

    /** Despierta a un aldeano concreto (al apagar la pausa). */
    public static void wake(World w, House h) {
        if (w == null) return;
        Entity e = entity(w, h);
        if (e instanceof Villager vi && !vi.hasAI()) vi.setAI(true);
    }

    /** Cuenta cuantos aldeanos hay despiertos y cuantos congelados (/lv village ai status). */
    public static int[] countAi(World w, Village v) {
        int activos = 0, pausados = 0, descargados = 0, sinAldeano = 0;
        for (House h : v.houses) {
            Pos3 at = (h.markVillager != null) ? h.markVillager : new Pos3(h.x, h.y, h.z);
            if (!w.isChunkLoaded(at.x >> 4, at.z >> 4)) { descargados++; continue; }
            Entity e = entity(w, h);
            if (!(e instanceof Villager vi)) { sinAldeano++; continue; }
            if (vi.hasAI()) activos++; else pausados++;
        }
        return new int[]{activos, pausados, descargados, sinAldeano};
    }

    private static Entity entity(World w, House h) {
        if (h.villagerId == null || h.villagerId.isEmpty()) return null;
        try {
            return w.getEntity(UUID.fromString(h.villagerId));
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    // ---------------- interno ----------------

    private static void spawn(World w, House house, String profId) {
        Pos3 at = house.markVillager;
        if (at == null) {
            // Sin marca de oro el aldeano acababa en (house.x, house.y+1, house.z), y
            // house.y es la altura del TERRENO cuando se sorteo la parcela: con
            // Skin.lift > 0 eso queda POR DEBAJO del suelo acabado y el aldeano aparecia
            // enterrado. Se busca hacia arriba desde el suelo real y, sobre todo, se
            // avisa: una casa sin marca es un fallo del .nbt y tiene que verse en el
            // log, no manifestarse como un aldeano raro tres semanas despues.
            at = huecoDeRespaldo(w, house);
            LiveVillagePlugin.LOGGER.warning(
                "El modelo '" + house.modelId + "' no trae bloque de oro (marca del aldeano). "
                + "Se usa una posicion de respaldo en (" + at.x + "," + at.y + "," + at.z
                + "). Revisa el .nbt.");
        }

        // Si el aldeano de esta casa sigue vivo se REUTILIZA: se le lleva a su marca y se
        // le reaplica oficio y nombre. Sin esto, cada /lv house regenerate dejaba suelto
        // al anterior (villagerId solo apuntaba al ultimo, sin forma de encontrar huerfanos).
        Entity existente = entity(w, house);
        if (existente instanceof Villager vivo && !vivo.isDead()) {
            moverA(vivo, w, at);
            aplicarProfesion(vivo, profId);
            ponerNombre(vivo, house.name, Cfg.VILLAGER_NAME_VISIBLE);
            return;
        }

        Location loc = new Location(w, at.x + 0.5, at.y, at.z + 0.5);
        Villager v = w.spawn(loc, Villager.class);
        aplicarProfesion(v, profId);
        ponerNombre(v, house.name, Cfg.VILLAGER_NAME_VISIBLE);
        v.setInvulnerable(true);
        v.setPersistent(true);            // que no lo despachen los despawns
        house.villagerId = v.getUniqueId().toString();
    }

    /** Posicion de emergencia cuando el .nbt no trae la marca de oro: primer hueco de
     *  dos bloques libres subiendo desde el suelo de la casa. */
    private static Pos3 huecoDeRespaldo(World w, House house) {
        int bx = house.x, bz = house.z;
        for (int dy = 1; dy <= 9; dy++) {
            int y = house.y + dy;
            if (w.getBlockAt(bx, y, bz).getType().isAir() && w.getBlockAt(bx, y + 1, bz).getType().isAir())
                return new Pos3(bx, y, bz);
        }
        return new Pos3(bx, house.y + 1, bz);
    }

    private static void aplicarProfesion(Villager v, String profId) {
        int i = indice(profId);
        if (i < 0) i = indice("farmer");
        v.setProfession(PROFESIONES[i]);
        v.setVillagerLevel(Cfg.VILLAGER_LEVEL);
    }

    /** Lleva una entidad ya existente al centro de ese bloque, mirando al norte. */
    private static void moverA(Entity e, World w, Pos3 at) {
        e.teleport(new Location(w, at.x + 0.5, at.y, at.z + 0.5, 0f, 0f));
    }

    private static void placeJob(World w, House house, String profId) {
        int i = indice(profId);
        if (i < 0) i = indice("farmer");
        VillageEngine.setTracked(w, house.markJob.x, house.markJob.y, house.markJob.z,
                                 PUESTOS[i].createBlockData(), house);
    }

    /**
     * Cartel de pie con el nombre del donador, mirando hacia la calle.
     *
     * El mod usa ROTATION_16 a mano (0=sur, 4=oeste, 8=norte, 12=este). Bukkit expone lo
     * mismo por la interfaz Rotatable, que acepta directamente las 4 BlockFace cardinales
     * (mas las 12 intermedias que aqui no hacen falta): no hace falta traducir a numero.
     */
    private static void placeSign(World w, House house) {
        BlockFace face = dirOf(house.facing);
        org.bukkit.block.data.BlockData data = Cfg.SIGN_BLOCK.createBlockData();
        if (data instanceof Rotatable r) r.setRotation(face);
        VillageEngine.setTracked(w, house.markSign.x, house.markSign.y, house.markSign.z, data, house);

        org.bukkit.block.BlockState be = w.getBlockAt(house.markSign.x, house.markSign.y, house.markSign.z).getState();
        if (be instanceof Sign s) {
            escribirCartel(s, house);
            s.update();
        }
    }

    /** Renglones del cartel + brillo (mismo efecto que la bolsa de tinta brillante), en
     *  las dos caras: un cartel de pie se puede leer por detras tambien. */
    private static void escribirCartel(Sign s, House house) {
        for (Side lado : Side.values()) {
            var cara = s.getSide(lado);
            cara.line(0, Component.text("Casa de"));
            cara.line(1, Component.text(house.name));
            cara.line(2, Component.text(professionLabel(house.profession)));
            cara.line(3, Component.text("#" + house.num));
            cara.setGlowingText(Cfg.SIGN_GLOWING);
        }
    }

    private static BlockFace dirOf(String facing) {
        if ("east".equals(facing))  return BlockFace.EAST;
        if ("west".equals(facing))  return BlockFace.WEST;
        if ("south".equals(facing)) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }

    private static String professionLabel(String id) {
        int i = indice(id);
        return i < 0 ? "" : PROF_ES[i];
    }

    /** Nombre visible sobre la entidad. */
    static void ponerNombre(Entity e, String nombre, boolean visible) {
        e.customName(Component.text(nombre));
        e.setCustomNameVisible(visible);
    }
}
