package com.vutocorp.livevillage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;

import java.util.Random;

/**
 * Toda la logica de "poner cosas en el mundo": plaza, scatter con rechazo, aplanado
 * con registro de cambios (para restaurar) y colocacion de la casa. Port de
 * VillageEngine.java del mod.
 *
 * Dos decisiones donde Bukkit no da un equivalente exacto, ambas a proposito:
 *
 *  - El mod escribe bloques con flag 3 (UPDATE_ALL: avisa a los vecinos y dispara
 *    fisica). Aqui se usa setBlockData(data, false), SIN fisica. Al vaciar el volumen
 *    de una casa con fisica activada la arena de alrededor cae dentro del hueco y el
 *    agua se cuela mientras se esta construyendo, porque cada bloque notifica al
 *    siguiente. Sin fisica el volumen queda como se escribio.
 *
 *  - Cfg.WAYSTONE_ID es dependencia blanda del mod Waystones, que es de NeoForge y no
 *    existe en un servidor Paper: Material solo conoce bloques vanilla. Aqui la marca
 *    de esmeralda de la plaza SIEMPRE recibe Cfg.PLAZA_FALLBACK (la campana). No es un
 *    olvido: es que ese bloque no puede existir en este entorno.
 */
public final class VillageEngine {

    private VillageEngine() {}

    // ---------- utilidades de bloques con registro de cambios ----------

    /** Mundo donde vive el pueblo. */
    public static World worldOf(Village v) {
        World w = (v == null || v.world == null) ? null : Bukkit.getWorld(v.world);
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    /** set() con registro de cambios, accesible desde otras clases (Villagers). */
    public static void setTracked(World w, int x, int y, int z, BlockData data, House house) {
        set(w, x, y, z, data, house);
    }

    private static void set(World w, int x, int y, int z, BlockData data, House house) {
        Block b = w.getBlockAt(x, y, z);
        BlockData old = b.getBlockData();
        if (old.matches(data)) return;
        if (house != null) house.recordChange(x, y, z, old.getAsString());
        b.setBlockData(data, false);
    }

    /** Coloca sin registrar (para la plaza, que no se "quita"). */
    private static void setPlain(World w, int x, int y, int z, BlockData data) {
        w.getBlockAt(x, y, z).setBlockData(data, false);
    }

    // ---------- TERRENO ----------

    /** Altura media del suelo real en una huella de tamaño arbitrario. */
    public static int averageGroundY(World w, int cx, int cz, int hx, int hz) {
        long sum = 0; int n = 0;
        for (int dx = -hx; dx <= hx; dx++) {
            for (int dz = -hz; dz <= hz; dz++) {
                sum += groundTopY(w, cx + dx, cz + dz);
                n++;
            }
        }
        return (int) Math.round((double) sum / n);
    }

    /**
     * Y del bloque de suelo real mas alto en (x,z), ignorando arboles, plantas y agua.
     *
     * getHighestBlockYAt devuelve el bloque MAS ALTO NO VACIO; el getHeight(WORLD_SURFACE)
     * del mod devuelve el primer AIRE por encima. Se suma 1 para arrancar en el mismo
     * sitio y que el barrido hacia abajo recorra exactamente las mismas alturas.
     */
    public static int groundTopY(World w, int x, int z) {
        int techo = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        int y = techo + 1;
        int guard = 0;
        while (guard++ < 80) {
            if (isGround(w.getBlockAt(x, y, z))) return y;
            y--;
        }
        return techo;
    }

    private static boolean isGround(Block b) {
        if (b.getType().isAir()) return false;
        if (esFluido(b)) return false;                       // agua / lava
        if (b.getBlockData().isReplaceable()) return false;  // pasto alto, nieve fina, plantas
        Material m = b.getType();
        if (Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m)
                || Tag.FLOWERS.isTagged(m) || Tag.SAPLINGS.isTagged(m)) return false;
        return true;
    }

    /** Equivalente a !getFluidState().isEmpty(): cuenta el agua/lava y los waterlogged. */
    private static boolean esFluido(Block b) {
        if (b.isLiquid()) return true;
        BlockData d = b.getBlockData();
        return d instanceof Waterlogged wl && wl.isWaterlogged();
    }

    // ---------- PLAZA ----------

    /**
     * Plaza desde la estructura de la skin, con la plaza procedural como respaldo.
     * La esmeralda del centro marca donde va el punto de reunion.
     */
    public static void buildPlaza(World w, Village v, int px, int py, int pz) {
        Skins.Skin skin = Skins.skin(v.skin == null ? Cfg.DEFAULT_SKIN : v.skin);
        Structures.Info info = (skin == null) ? null : Structures.info(skin.plaza);
        Structure tpl = (skin == null) ? null : Structures.cargar(skin.plaza);
        if (info == null || tpl == null) { buildPlazaProcedural(w, px, py, pz); return; }

        int fw = info.sizeX, fd = info.sizeZ, fh = info.sizeY;   // la plaza no rota (sin fachada)
        int minX = px - fw / 2, minZ = pz - fd / 2, baseY = py;
        // El tamaño manda sobre Cfg: si cambias el .nbt de la plaza, el bloqueo del A* y el
        // arranque de las avenidas se ajustan solos sin tocar codigo.
        v.plazaHalf = Math.min(fw, fd) / 2;

        BlockData air = Material.AIR.createBlockData();
        BlockData fillB = Cfg.FILL_BLOCK.createBlockData();
        BlockData[] topWas = new BlockData[fw * fd];
        for (int dx = 0; dx < fw; dx++) {
            for (int dz = 0; dz < fd; dz++) {
                int x = minX + dx, z = minZ + dz;
                topWas[dx * fd + dz] = w.getBlockAt(x, groundTopY(w, x, z), z).getBlockData();
                for (int dy = 0; dy <= fh + Cfg.CLEAR_HEIGHT; dy++)
                    setPlain(w, x, baseY + dy, z, air);
                int y = baseY - 1; int g = 0;
                while (g++ < Cfg.MAX_FILL_DOWN) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isAir() && !esFluido(b) && !b.getBlockData().isReplaceable()) break;
                    setPlain(w, x, y, z, fillB);
                    y--;
                }
            }
        }

        // La plaza NO pertenece a ninguna casa: se pega sin registrar cambios.
        int[] origin = Structures.originFor(StructureRotation.NONE, fw, fd, minX, minZ);
        Structures.paste(w, null, tpl, StructureRotation.NONE, origin[0], origin[1],
                         minX, baseY, minZ, fw, fh, fd);

        // tapar los huecos de la capa base con el terreno original
        if (Cfg.PATCH_BASE_HOLES) {
            for (int dx = 0; dx < fw; dx++)
                for (int dz = 0; dz < fd; dz++) {
                    Block here = w.getBlockAt(minX + dx, baseY, minZ + dz);
                    if (here.getType().isAir() || here.getBlockData().isReplaceable()) {
                        BlockData t = topWas[dx * fd + dz];
                        setPlain(w, minX + dx, baseY, minZ + dz,
                                 (t == null || t.getMaterial().isAir()) ? fillB : t);
                    }
                }
        }
        if (Cfg.EMPTY_CONTAINERS) Structures.emptyContainers(w, minX, baseY, minZ, fw, fh, fd);

        // esmeralda = punto de reunion (ver nota de Waystones en la cabecera de la clase)
        Pos3 mark = Structures.findMark(w, Cfg.MARK_JOB, minX, baseY, minZ, fw, fh, fd);
        v.waystone = mark;
        if (mark != null) setPlain(w, mark.x, mark.y, mark.z, Cfg.PLAZA_FALLBACK.createBlockData());
    }

    public static void buildPlazaProcedural(World w, int px, int py, int pz) {
        BlockData floor = Cfg.PLAZA_BLOCK.createBlockData();
        BlockData air = Material.AIR.createBlockData();
        BlockData fill = Cfg.FILL_BLOCK.createBlockData();
        for (int dx = -Cfg.PLAZA_HALF; dx <= Cfg.PLAZA_HALF; dx++) {
            for (int dz = -Cfg.PLAZA_HALF; dz <= Cfg.PLAZA_HALF; dz++) {
                int x = px + dx, z = pz + dz;
                if (w.getBlockAt(x, py - 1, z).getType().isAir()) setPlain(w, x, py - 1, z, fill);
                setPlain(w, x, py, z, floor);
                for (int dy = 1; dy <= Cfg.CLEAR_HEIGHT; dy++) setPlain(w, x, py + dy, z, air);
            }
        }
    }

    // ---------- SCATTER ----------

    /** Centro valido para una casa de huella (2*hx+1)x(2*hz+1). null si no encuentra. */
    public static int[] findSpot(World w, Village v, int hx, int hz) {
        Random r = new Random();
        // Arrancar SIEMPRE en INITIAL_RADIUS no escala: con el centro lleno, los 500 intentos
        // caen todos en zona ocupada y la donacion se rechaza (pasaba a partir de ~100 casas).
        // Se empieza un poco por DENTRO de la casa mas lejana: asi se rellenan los huecos del
        // borde y, si no hay, se crece hacia fuera enseguida.
        double spacing = 2.0 * Math.max(hx, hz) + Cfg.HOUSE_GAP;
        double frontier = 0;
        for (House h : v.houses) {
            double dx = h.x - v.plazaX, dz = h.z - v.plazaZ;
            frontier = Math.max(frontier, Math.sqrt(dx * dx + dz * dz));
        }
        double radius = Math.max(Cfg.INITIAL_RADIUS, frontier - 2.0 * spacing);
        for (int tries = 1; tries <= Cfg.MAX_TRIES; tries++) {
            double angle = r.nextDouble() * Math.PI * 2.0;
            double rr = radius + r.nextDouble() * Cfg.RADIUS_STEP;
            int x = v.plazaX + (int) Math.round(Math.cos(angle) * rr);
            int z = v.plazaZ + (int) Math.round(Math.sin(angle) * rr);
            if (farEnough(v, x, z, Math.max(hx, hz)) && plotIsDry(w, x, z, hx, hz)
                    && plotFreeOfPaths(v, x, z, hx, hz)) return new int[]{x, z};
            if (tries % 20 == 0) radius += Cfg.RADIUS_STEP; // cuesta encajar -> ensancha
        }
        return null;
    }

    /** true si NINGUNA columna de la huella tiene agua/lava en la superficie. */
    private static boolean plotIsDry(World w, int cx, int cz, int hx, int hz) {
        for (int dx = -hx; dx <= hx; dx++)
            for (int dz = -hz; dz <= hz; dz++)
                if (isWaterColumn(w, cx + dx, cz + dz)) return false;
        return true;
    }

    /** true si el bloque superior no-aire de esta columna es fluido. */
    private static boolean isWaterColumn(World w, int x, int z) {
        int top = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        return esFluido(w.getBlockAt(x, top, z));
    }

    /** La huella (mas 1 de margen) no debe pisar ningun camino ya trazado. */
    private static boolean plotFreeOfPaths(Village v, int cx, int cz, int hx, int hz) {
        int mx = hx + 1, mz = hz + 1;
        for (int dx = -mx; dx <= mx; dx++)
            for (int dz = -mz; dz <= mz; dz++)
                if (v.pathCells.contains(clave(cx + dx, cz + dz))) return false;
        return true;
    }

    /** Empaquetado (x,z) -> long, igual que en el mod: las celdas guardadas deben coincidir. */
    public static long clave(int x, int z) {
        return (((long) x) & 0xffffffffL) | ((((long) z) & 0xffffffffL) << 32);
    }

    /** Distancia minima = mitad de una casa + mitad de la otra + hueco libre. No es fija:
     *  con modelos de 9x9 y 11x11 mezclados, una constante los solapaba o los separaba de mas. */
    private static boolean farEnough(Village v, int cx, int cz, int half) {
        for (House h : v.houses) {
            int dx = h.x - cx, dz = h.z - cz;
            int need = half + h.halfMax() + Cfg.HOUSE_GAP;
            if (Math.max(Math.abs(dx), Math.abs(dz)) < need) return false;
        }
        return true;
    }

    // ---------- CASA ----------

    /** Coloca la casa en (cx,cz) orientada hacia la plaza. */
    public static void buildHouse(World w, Village v, House house, int cx, int cy, int cz) {
        Skins.Model model = Skins.model(house.modelId);
        if (model != null) {
            Structures.Info info = Structures.info(model.structure);
            if (info != null) { buildFromStructure(w, v, house, cx, cz, model, info); return; }
        }
        buildProvisional(w, v, house, cx, cy, cz);
    }

    /**
     * Casa real desde .nbt. Orden: orientar -> nivelar -> aplanar -> pegar -> leer y borrar
     * las marcas -> tapar huecos de la capa base -> vaciar cofres -> camino.
     */
    private static void buildFromStructure(World w, Village v, House house, int cx, int cz,
                                           Skins.Model model, Structures.Info info) {
        // 1) hacia donde tiene que mirar la fachada, y rotacion necesaria
        BlockFace want = towardPlaza(v, cx, cz);
        house.facing = nombreDe(want);
        StructureRotation rot = Structures.rotationFor(info.front, want);
        int fw = Structures.footprintX(rot, info.sizeX, info.sizeZ);
        int fd = Structures.footprintZ(rot, info.sizeX, info.sizeZ);
        int fh = info.sizeY;
        house.modelId = model.id;
        house.sizeX = fw;
        house.sizeZ = fd;

        int hx = fw / 2, hz = fd / 2;
        int minX = cx - hx, minZ = cz - hz;

        // Punto al que tiene que llegar el ramal. Perpendicular a la fachada se sale un
        // bloque de la huella. PARALELO a la fachada NO se usa el centro de la cara: se usa
        // la columna REAL de la puerta. En japonhouse1/2 la puerta esta centrada y sale lo
        // mismo; en una casa con la puerta descentrada (japonhouse3) el centro de la cara es
        // muro macizo y el camino llegaba de lado.
        int doorX = cx + want.getModX() * (hx + 1);
        int doorZ = cz + want.getModZ() * (hz + 1);
        if (info.doorX >= 0) {
            int[] dw = Structures.worldXZ(rot, info.sizeX, info.sizeZ, minX, minZ,
                                          info.doorX, info.doorZ);
            if (want.getModX() != 0) doorZ = dw[1];   // fachada este/oeste -> alinear en Z
            else                     doorX = dw[0];   // fachada norte/sur  -> alinear en X
        }

        // 2) altura: media de la huella, subida si el camino pasa mas alto por delante
        int groundY = levelWithPath(w, averageGroundY(w, cx, cz, hx, hz), doorX, doorZ, house.facing);
        // La skin puede pedir que sus casas se asienten N bloques mas arriba (Skin.lift).
        // El CAMINO no sube: sale a groundY, asi que queda un escalon de N en la puerta,
        // que jugadores y aldeanos suben sin problema.
        int baseY = groundY + model.lift();

        BlockData air = Material.AIR.createBlockData();
        BlockData fillB = Cfg.FILL_BLOCK.createBlockData();

        // 3) aplanar: guardar la superficie original (para tapar huecos luego), despejar
        //    por encima y rellenar por debajo hasta solido.
        BlockData[] topWas = new BlockData[fw * fd];
        for (int dx = 0; dx < fw; dx++) {
            for (int dz = 0; dz < fd; dz++) {
                int x = minX + dx, z = minZ + dz;
                topWas[dx * fd + dz] = w.getBlockAt(x, groundTopY(w, x, z), z).getBlockData();
                for (int dy = 0; dy <= fh + Cfg.CLEAR_HEIGHT; dy++)
                    set(w, x, baseY + dy, z, air, house);
                int y = baseY - 1; int g = 0;
                while (g++ < Cfg.MAX_FILL_DOWN) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isAir() && !esFluido(b) && !b.getBlockData().isReplaceable()) break;
                    set(w, x, y, z, fillB, house);
                    y--;
                }
            }
        }

        // 4) pegar la estructura (registra solo las diferencias reales)
        Structure tpl = Structures.cargar(model.structure);
        if (tpl == null) { buildProvisional(w, v, house, cx, groundY, cz); return; }
        int[] origin = Structures.originFor(rot, info.sizeX, info.sizeZ, minX, minZ);
        Structures.paste(w, house, tpl, rot, origin[0], origin[1], minX, baseY, minZ, fw, fh, fd);

        // 5) tapar los huecos de la capa base con el terreno que habia ahi. SOLO si la casa
        //    se asienta en el suelo (lift == 0): con la casa levantada esa capa esta un
        //    bloque POR ENCIMA del terreno y taparla dibujaba un anillo de tierra flotando
        //    alrededor de la casa. Va ANTES de vaciar las marcas: si una marca cayera en la
        //    capa base, el parcheo la taparia con tierra en vez de dejar el hueco del aldeano.
        if (Cfg.PATCH_BASE_HOLES && model.lift() == 0) {
            for (int dx = 0; dx < fw; dx++)
                for (int dz = 0; dz < fd; dz++) {
                    Block here = w.getBlockAt(minX + dx, baseY, minZ + dz);
                    if (here.getType().isAir() || here.getBlockData().isReplaceable()) {
                        BlockData t = topWas[dx * fd + dz];
                        set(w, minX + dx, baseY, minZ + dz,
                            (t == null || t.getMaterial().isAir()) ? fillB : t, house);
                    }
                }
        }

        // 6) leer las marcas y sustituirlas por aire
        house.markVillager = Structures.findMark(w, Cfg.MARK_VILLAGER, minX, baseY, minZ, fw, fh, fd);
        house.markJob      = Structures.findMark(w, Cfg.MARK_JOB,      minX, baseY, minZ, fw, fh, fd);
        house.markSign     = Structures.findMark(w, Cfg.MARK_SIGN,     minX, baseY, minZ, fw, fh, fd);
        if (house.markVillager != null) set(w, house.markVillager.x, house.markVillager.y, house.markVillager.z, air, house);
        if (house.markJob != null)      set(w, house.markJob.x, house.markJob.y, house.markJob.z, air, house);
        if (house.markSign != null)     set(w, house.markSign.x, house.markSign.y, house.markSign.z, air, house);

        // 7) cofres vacios
        if (Cfg.EMPTY_CONTAINERS) Structures.emptyContainers(w, minX, baseY, minZ, fw, fh, fd);

        // 7b) aldeano + puesto de trabajo + cartel: Fase 3 (Villagers todavia no portado).
        // 8) camino desde la puerta, al nivel del TERRENO (no de la casa levantada)
        PathBuilder.build(w, v, house, doorX, doorZ, groundY, house.facing);
    }

    /** Lado de la casa que mira a la plaza. */
    private static BlockFace towardPlaza(Village v, int cx, int cz) {
        int ddx = v.plazaX - cx, ddz = v.plazaZ - cz;
        if (Math.abs(ddx) >= Math.abs(ddz)) return (ddx >= 0) ? BlockFace.EAST : BlockFace.WEST;
        return (ddz >= 0) ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static String nombreDe(BlockFace f) {
        switch (f) {
            case EAST:  return "east";
            case WEST:  return "west";
            case SOUTH: return "south";
            default:    return "north";
        }
    }

    /** Cabaña provisional (respaldo cuando falta la estructura). */
    private static void buildProvisional(World w, Village v, House house, int cx, int cy, int cz) {
        // Lado hacia la plaza. Se calcula ANTES de aplanar porque la altura de la casa
        // depende de por donde va a salir el camino.
        int ddx = v.plazaX - cx, ddz = v.plazaZ - cz;
        String facing;
        if (Math.abs(ddx) >= Math.abs(ddz)) facing = (ddx >= 0) ? "east" : "west";
        else facing = (ddz >= 0) ? "south" : "north";
        house.facing = facing;
        int doorX = cx, doorZ = cz;
        switch (facing) {
            case "east":  doorX = cx + Cfg.PLOT_HALF + 1; break;
            case "west":  doorX = cx - Cfg.PLOT_HALF - 1; break;
            case "south": doorZ = cz + Cfg.PLOT_HALF + 1; break;
            default:      doorZ = cz - Cfg.PLOT_HALF - 1; break;
        }

        // NIVELADO: suelo = altura media de la parcela, pero nunca por debajo de la altura a
        // la que el camino va a pasar por delante de la puerta.
        int fy = levelWithPath(w, cy, doorX, doorZ, facing);

        BlockData floor = Cfg.FLOOR_BLOCK.createBlockData();
        BlockData wall  = Cfg.WALL_BLOCK.createBlockData();
        BlockData roof  = Cfg.ROOF_BLOCK.createBlockData();
        BlockData air   = Material.AIR.createBlockData();
        BlockData fill  = Cfg.FILL_BLOCK.createBlockData();
        int H = Cfg.PLOT_HALF;

        for (int dx = -H; dx <= H; dx++) {
            for (int dz = -H; dz <= H; dz++) {
                int x = cx + dx, z = cz + dz;
                for (int dy = 1; dy <= Cfg.CLEAR_HEIGHT; dy++) set(w, x, fy + dy, z, air, house);
                set(w, x, fy, z, floor, house);
                int y = fy - 1; int g = 0;
                while (g++ < Cfg.MAX_FILL_DOWN) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isAir() && !esFluido(b) && !b.getBlockData().isReplaceable()) break;
                    set(w, x, y, z, fill, house);
                    y--;
                }
            }
        }

        for (int dy = 1; dy <= Cfg.WALL_HEIGHT; dy++)
            for (int dx = -H; dx <= H; dx++)
                for (int dz = -H; dz <= H; dz++) {
                    if (Math.abs(dx) != H && Math.abs(dz) != H) continue;
                    boolean isDoor = (dy <= 2) && isDoorway(facing, dx, dz, H);
                    set(w, cx + dx, fy + dy, cz + dz, isDoor ? air : wall, house);
                }

        for (int dx = -H; dx <= H; dx++)
            for (int dz = -H; dz <= H; dz++)
                set(w, cx + dx, fy + Cfg.WALL_HEIGHT + 1, cz + dz, roof, house);

        PathBuilder.build(w, v, house, doorX, doorZ, fy, facing);
    }

    /**
     * Altura del suelo de la casa. Parte de la media de la parcela, pero si el camino va a
     * pasar por delante de la puerta MAS ALTO, sube la casa hasta ese nivel (con tope
     * HOUSE_LEVEL_MAX_RAISE). Asi el ramal sale de la puerta plano en vez de hundirse.
     * Nunca baja la casa: si la parcela esta mas alta, el camino sube en terraza.
     */
    private static int levelWithPath(World w, int avgGroundY, int doorX, int doorZ, String facing) {
        int dx = 0, dz = 0;
        switch (facing) {
            case "east":  dx = 1;  break;
            case "west":  dx = -1; break;
            case "south": dz = 1;  break;
            default:      dz = -1; break;
        }
        // Se muestrea donde el camino ya sigue al terreno (las 2 primeras celdas van clavadas
        // a la altura de la puerta, asi que la referencia esta a partir de la tercera).
        int approach = Integer.MIN_VALUE;
        for (int d = 2; d <= 3; d++) {
            int x = doorX + dx * d, z = doorZ + dz * d;
            if (isWaterColumn(w, x, z)) continue;
            approach = Math.max(approach, groundTopY(w, x, z));
        }
        if (approach == Integer.MIN_VALUE || approach <= avgGroundY) return avgGroundY;
        return Math.min(approach, avgGroundY + Cfg.HOUSE_LEVEL_MAX_RAISE);
    }

    private static boolean isDoorway(String facing, int dx, int dz, int H) {
        switch (facing) {
            case "east":  return dx == H  && dz == 0;
            case "west":  return dx == -H && dz == 0;
            case "south": return dz == H  && dx == 0;
            default:      return dz == -H && dx == 0;
        }
    }

    // ---------- RESTAURAR (quitar casa) ----------

    /**
     * Deshace lo que hizo esta casa Y ademas borra de la red las celdas de camino suyas.
     *
     * Sin lo segundo hay un fallo real: restore() devuelve el terreno del ramal a su estado
     * original (esos bloques estan en su lista de cambios), pero v.pathCells se quedaria con
     * las celdas. El A* seguiria creyendo que ahi hay calle y las casas nuevas engancharian
     * con un camino fantasma que ya no existe en el mundo.
     *
     * Se distingue camino de casa por posicion: todo lo que caiga FUERA de la huella es ramal
     * o farol. Cada celda pertenece a una sola casa, asi que borrarlas no deja huecos en
     * calles ajenas.
     */
    public static void restoreAndForget(World w, Village v, House house) {
        int hx = house.halfX(), hz = house.halfZ();
        for (House.Cambio c : house.cambios) {
            if (Math.abs(c.x - house.x) <= hx && Math.abs(c.z - house.z) <= hz) continue;
            long k = clave(c.x, c.z);
            v.pathCells.remove(k);
            v.lampCells.remove(k);
            v.treeCells.remove(k);
        }
        restore(w, house);
    }

    public static void restore(World w, House house) {
        // deshacer en orden inverso
        for (int i = house.cambios.size() - 1; i >= 0; i--) {
            House.Cambio c = house.cambios.get(i);
            try {
                w.getBlockAt(c.x, c.y, c.z).setBlockData(Bukkit.createBlockData(c.antes), false);
            } catch (IllegalArgumentException e) {
                // Un BlockData que ya no existe (bloque de una version anterior) no puede
                // impedir que se restaure el resto de la casa.
            }
        }
        house.cambios.clear();
    }

    // ---------- ANUNCIO ----------

    public static void announce(Village v, House h, int number) {
        Component msg = Component.text("★ ", NamedTextColor.YELLOW)
            .append(Component.text("¡Nueva casa en ", NamedTextColor.AQUA))
            .append(Component.text(v.name, NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text("! Donada por ", NamedTextColor.AQUA))
            .append(Component.text(h.name, NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
            .append(Component.text(" (casa #" + number + ") ", NamedTextColor.AQUA))
            .append(Component.text("★", NamedTextColor.YELLOW));
        Bukkit.broadcast(msg);
    }
}
