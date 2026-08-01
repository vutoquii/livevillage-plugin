package com.vutocorp.livevillage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Carga y pegado de estructuras (.nbt de structure block). Port de Structures.java
 * del mod.
 *
 * Diferencia grande y a favor: el mod tiene que leer el NBT CRUDO (template.save())
 * porque StructureBlockInfo cambia de forma entre versiones de Minecraft. Bukkit
 * expone la paleta ya decodificada (Palette.getBlocks() -> BlockState con posicion
 * y BlockData), asi que aqui no hay ningun parseo a mano. Comprobado en el spike
 * contra japonhouse1: las 3 marcas y las 2 puertas salen tal cual.
 *
 * La orientacion NO se pide al constructor: se deduce de la PUERTA (ver info()),
 * igual que en el mod. El lapis solo es respaldo si no hay ninguna puerta.
 */
public final class Structures {

    private Structures() {}

    /** Datos de una estructura, leidos una sola vez y cacheados. */
    public static final class Info {
        public final int sizeX, sizeY, sizeZ;
        /** Fachada SIN rotar. null = la estructura no tiene frente (plaza, poste). */
        public final BlockFace front;
        public final boolean hasVillager, hasJob, hasSign;
        /** Solo en postes de farol: columna local del mastil y hacia donde sale el brazo. */
        public final int lampX, lampZ;
        public final BlockFace lampFront;
        /** Columna local (SIN rotar) de la puerta que define la fachada. -1 = no hay.
         *  El ramal tiene que llegar A LA PUERTA, no al centro de la cara: en una casa
         *  con la puerta descentrada (japonhouse3) el centro es muro macizo. */
        public final int doorX, doorZ;
        /** Posicion LOCAL (sin rotar) de cada marca. null si esa marca no esta.
         *  El mod las busca escaneando el mundo despues de pegar; tenerlas de antes
         *  permite predecir donde caeran, que es como se comprueba que la rotacion
         *  esta bien calculada (ver /lv model test). */
        public final Pos3 markVillager, markJob, markSign;

        Info(int sx, int sy, int sz, BlockFace f, boolean hv, boolean hj, boolean hs,
             int lx, int lz, BlockFace lf, int dx, int dz,
             Pos3 mv, Pos3 mj, Pos3 ms) {
            sizeX = sx; sizeY = sy; sizeZ = sz; front = f;
            hasVillager = hv; hasJob = hj; hasSign = hs;
            lampX = lx; lampZ = lz; lampFront = lf;
            doorX = dx; doorZ = dz;
            markVillager = mv; markJob = mj; markSign = ms;
        }
        public boolean oddFootprint() { return (sizeX % 2 == 1) && (sizeZ % 2 == 1); }
    }

    private static final Map<String, Info> CACHE = new HashMap<>();
    private static final Map<String, Structure> CARGADAS = new HashMap<>();
    private static final Set<String> NO_ESTAN = new HashSet<>();

    public static final String SRC_CARPETA = "carpeta del plugin";
    public static final String SRC_JAR     = "jar del plugin";
    public static final String SRC_NONE    = "no encontrada";

    /** Carpeta donde el admin puede pisar una estructura del jar. La fija el plugin al arrancar. */
    private static File carpetaEstructuras;

    public static void usarCarpeta(File carpeta) { carpetaEstructuras = carpeta; }

    /**
     * Resolucion en DOS niveles, igual de intencion que en el mod:
     *   1) plugins/LiveVillage/structures/<nombre>.nbt   (lo que ponga el admin)
     *   2) recurso del jar /structures/livevillage/<nombre>.nbt
     * El (1) gana, asi que se puede retocar una casa con un structure block y pisarla
     * sin recompilar. El (2) hace que funcione recien instalado.
     */
    public static Structure cargar(String nombre) {
        Structure hit = CARGADAS.get(nombre);
        if (hit != null) return hit;
        if (NO_ESTAN.contains(nombre)) return null;

        Structure s = null;
        try {
            if (carpetaEstructuras != null) {
                File f = new File(carpetaEstructuras, nombre + ".nbt");
                if (f.isFile()) s = Bukkit.getStructureManager().loadStructure(f);
            }
            if (s == null) {
                try (InputStream in = Structures.class.getResourceAsStream(
                        "/structures/livevillage/" + nombre + ".nbt")) {
                    if (in != null) s = Bukkit.getStructureManager().loadStructure(in);
                }
            }
        } catch (Exception e) {
            s = null;
        }
        if (s == null) { NO_ESTAN.add(nombre); return null; }
        CARGADAS.put(nombre, s);
        return s;
    }

    public static String source(String nombre) {
        if (carpetaEstructuras != null && new File(carpetaEstructuras, nombre + ".nbt").isFile())
            return SRC_CARPETA;
        return cargar(nombre) != null ? SRC_JAR : SRC_NONE;
    }

    /**
     * Tamaño, fachada y marcas. null si la estructura no existe.
     *
     * La fachada sale de la PUERTA: se busca la puerta (mitad inferior) cuya posicion
     * sobresale mas hacia el lado contrario a su facing, que es la que da a la calle.
     * Asi las puertas interiores no confunden.
     */
    public static Info info(String nombre) {
        Info cached = CACHE.get(nombre);
        if (cached != null) return cached;

        Structure s = cargar(nombre);
        if (s == null) return null;

        BlockVector size = s.getSize();
        int sx = size.getBlockX(), sy = size.getBlockY(), sz = size.getBlockZ();

        List<Palette> paletas = s.getPalettes();
        if (paletas.isEmpty()) return null;

        double ccx = (sx - 1) / 2.0, ccz = (sz - 1) / 2.0;
        boolean hv = false, hj = false, hs = false;
        BlockFace front = null;
        double bestOut = Double.NEGATIVE_INFINITY;
        int[] lapisPos = null, doorPos = null, lanternPos = null, mastPos = null;
        Pos3 mv = null, mj = null, ms = null;

        for (org.bukkit.block.BlockState bs : paletas.get(0).getBlocks()) {
            Material m = bs.getType();
            int px = bs.getX(), py = bs.getY(), pz = bs.getZ();

            if (m == Cfg.MARK_VILLAGER) { hv = true; if (mv == null) mv = new Pos3(px, py, pz); }
            else if (m == Cfg.MARK_JOB) { hj = true; if (mj == null) mj = new Pos3(px, py, pz); }
            else if (m == Cfg.MARK_SIGN) {
                hs = true;
                if (ms == null) ms = new Pos3(px, py, pz);
                if (lapisPos == null) lapisPos = new int[]{px, pz};
            }

            if (m == Material.LANTERN && lanternPos == null) lanternPos = new int[]{px, pz};
            // mastil = primera columna apoyada en el suelo (bloque solido en y=0)
            if (py == 0 && mastPos == null && m != Material.AIR) mastPos = new int[]{px, pz};

            BlockData bd = bs.getBlockData();
            if (bd instanceof Door d && d.getHalf() == Bisected.Half.BOTTOM) {
                BlockFace out = d.getFacing().getOppositeFace();      // hacia la calle
                double proj = (px - ccx) * out.getModX() + (pz - ccz) * out.getModZ();
                if (proj > bestOut) { bestOut = proj; front = out; doorPos = new int[]{px, pz}; }
            }
        }

        // respaldo: sin puertas, el lapis marca la fachada
        if (front == null && lapisPos != null) {
            double dx = lapisPos[0] - ccx, dz = lapisPos[1] - ccz;
            front = (Math.abs(dx) >= Math.abs(dz))
                    ? (dx >= 0 ? BlockFace.EAST : BlockFace.WEST)
                    : (dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH);
        }

        int lx = (mastPos != null) ? mastPos[0] : 0;
        int lz = (mastPos != null) ? mastPos[1] : 0;
        BlockFace lampFront = null;
        if (mastPos != null && lanternPos != null) {
            int dx = lanternPos[0] - mastPos[0], dz = lanternPos[1] - mastPos[1];
            if (Math.abs(dx) >= Math.abs(dz) && dx != 0) lampFront = dx > 0 ? BlockFace.EAST : BlockFace.WEST;
            else if (dz != 0) lampFront = dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
        }

        Info info = new Info(sx, sy, sz, front, hv, hj, hs, lx, lz, lampFront,
                             doorPos != null ? doorPos[0] : -1,
                             doorPos != null ? doorPos[1] : -1,
                             mv, mj, ms);
        CACHE.put(nombre, info);
        return info;
    }

    public static void clearCache() { CACHE.clear(); CARGADAS.clear(); NO_ESTAN.clear(); }

    // ---------------- rotacion ----------------

    /**
     * Indice 2D de una direccion en el orden de Minecraft (SOUTH=0, WEST=1, NORTH=2, EAST=3).
     * A mano y no con una utilidad de la API, igual que en el mod: un switch no se rompe
     * si la libreria mueve el metodo de sitio.
     */
    private static int idx2D(BlockFace f) {
        switch (f) {
            case SOUTH: return 0;
            case WEST:  return 1;
            case NORTH: return 2;
            case EAST:  return 3;
            default:    return -1;
        }
    }

    /** Rotacion que lleva la fachada 'from' a apuntar a 'to'. Sin fachada -> no rota. */
    public static StructureRotation rotationFor(BlockFace from, BlockFace to) {
        if (from == null || to == null) return StructureRotation.NONE;
        int a = idx2D(from), b = idx2D(to);
        if (a < 0 || b < 0) return StructureRotation.NONE;
        int d = ((b - a) % 4 + 4) % 4;
        switch (d) {
            case 1:  return StructureRotation.CLOCKWISE_90;
            case 2:  return StructureRotation.CLOCKWISE_180;
            case 3:  return StructureRotation.COUNTERCLOCKWISE_90;
            default: return StructureRotation.NONE;
        }
    }

    /** Rota (x,z) locales sobre el pivote (0,0), igual que hace vanilla. */
    public static int[] rotXZ(int x, int z, StructureRotation r) {
        if (r == StructureRotation.CLOCKWISE_90)        return new int[]{-z, x};
        if (r == StructureRotation.CLOCKWISE_180)       return new int[]{-x, -z};
        if (r == StructureRotation.COUNTERCLOCKWISE_90) return new int[]{z, -x};
        return new int[]{x, z};
    }

    /** Desplazamiento de la esquina minima al rotar sobre (0,0). */
    private static int offX(StructureRotation r, int sx, int sz) {
        return (r == StructureRotation.CLOCKWISE_90) ? -(sz - 1)
             : (r == StructureRotation.CLOCKWISE_180 ? -(sx - 1) : 0);
    }
    private static int offZ(StructureRotation r, int sx, int sz) {
        return (r == StructureRotation.CLOCKWISE_180) ? -(sz - 1)
             : (r == StructureRotation.COUNTERCLOCKWISE_90 ? -(sx - 1) : 0);
    }

    /** Esquina minima en el mundo de una estructura pegada con ese origen (la inversa de originFor). */
    public static int minXOf(StructureRotation r, int sx, int sz, int originX) { return originX + offX(r, sx, sz); }
    public static int minZOf(StructureRotation r, int sx, int sz, int originZ) { return originZ + offZ(r, sx, sz); }

    public static int footprintX(StructureRotation r, int sx, int sz) { return swapsAxes(r) ? sz : sx; }
    public static int footprintZ(StructureRotation r, int sx, int sz) { return swapsAxes(r) ? sx : sz; }

    private static boolean swapsAxes(StructureRotation r) {
        return r == StructureRotation.CLOCKWISE_90 || r == StructureRotation.COUNTERCLOCKWISE_90;
    }

    /**
     * Punto que hay que pasar a place() para que la huella rotada empiece en (minX,minZ).
     * Al rotar sobre el pivote (0,0,0) la estructura se va a coordenadas negativas, asi
     * que hay que compensar:
     *   CW90 :(x,z)->(-z, x)    CW180:(x,z)->(-x,-z)    CCW90:(x,z)->( z,-x)
     */
    public static int[] originFor(StructureRotation r, int sx, int sz, int minX, int minZ) {
        return new int[]{ minX - offX(r, sx, sz), minZ - offZ(r, sx, sz) };
    }

    /** Coordenada de MUNDO (x,z) de un punto local (lx,lz) ya rotado y colocado. */
    public static int[] worldXZ(StructureRotation r, int sx, int sz, int minX, int minZ, int lx, int lz) {
        int[] o = originFor(r, sx, sz, minX, minZ);
        int[] p = rotXZ(lx, lz, r);
        return new int[]{ o[0] + p[0], o[1] + p[1] };
    }

    // ---------------- pegado ----------------

    /**
     * Pega la estructura registrando en 'house' SOLO los bloques que de verdad cambian
     * (se fotografia el volumen antes, se pega, y se compara). Asi el guardado no engorda
     * con miles de aire->aire y 'remove' sigue restaurando exacto. house puede ser null
     * (la plaza no pertenece a ninguna casa y no se restaura).
     */
    public static void paste(World w, House house, Structure tpl, StructureRotation rot,
                             int originX, int originZ, int minX, int baseY, int minZ,
                             int fw, int fh, int fd) {
        // 1) foto del volumen ANTES (solo en memoria)
        BlockData[] before = new BlockData[fw * fh * fd];
        for (int dx = 0; dx < fw; dx++)
            for (int dy = 0; dy < fh; dy++)
                for (int dz = 0; dz < fd; dz++)
                    before[(dx * fh + dy) * fd + dz] =
                            w.getBlockAt(minX + dx, baseY + dy, minZ + dz).getBlockData();

        // 2) pegar
        tpl.place(new Location(w, originX, baseY, originZ), true, rot, Mirror.NONE, 0, 1.0f, new Random());

        // 3) registrar solo las diferencias
        if (house == null) return;
        for (int dx = 0; dx < fw; dx++) {
            for (int dy = 0; dy < fh; dy++) {
                for (int dz = 0; dz < fd; dz++) {
                    BlockData old = before[(dx * fh + dy) * fd + dz];
                    BlockData ahora = w.getBlockAt(minX + dx, baseY + dy, minZ + dz).getBlockData();
                    if (!old.matches(ahora))
                        house.recordChange(minX + dx, baseY + dy, minZ + dz, old.getAsString());
                }
            }
        }
    }

    /** Busca un bloque-marca dentro del volumen. null si no aparece. */
    public static Pos3 findMark(World w, Material mark, int minX, int baseY, int minZ,
                                int fw, int fh, int fd) {
        for (int dy = 0; dy < fh; dy++)
            for (int dx = 0; dx < fw; dx++)
                for (int dz = 0; dz < fd; dz++)
                    if (w.getBlockAt(minX + dx, baseY + dy, minZ + dz).getType() == mark)
                        return new Pos3(minX + dx, baseY + dy, minZ + dz);
        return null;
    }

    /** Vacia todos los contenedores del volumen (decision del usuario: cofres vacios). */
    public static int emptyContainers(World w, int minX, int baseY, int minZ, int fw, int fh, int fd) {
        int n = 0;
        for (int dx = 0; dx < fw; dx++)
            for (int dy = 0; dy < fh; dy++)
                for (int dz = 0; dz < fd; dz++) {
                    org.bukkit.block.BlockState be = w.getBlockAt(minX + dx, baseY + dy, minZ + dz).getState();
                    if (be instanceof Container c) { c.getInventory().clear(); c.update(); n++; }
                }
        return n;
    }
}
