package com.vutocorp.livevillage;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Caminos del pueblo. Port de PathBuilder.java del mod. Un A* traza la ruta desde la
 * puerta de la casa hasta la red existente, rodeando las demas casas; el camino sigue
 * el terreno, con ancho 2, y todos los bloques se registran en la casa para restaurar.
 *
 * Es el fichero que MENOS cambia del mod: el A*, el enderezado y el reparto de faroles
 * son logica propia. Solo cambian los tipos de la capa de bloques.
 *
 * BlockFace de Bukkit no tiene getClockWise()/getAxis() como el Direction de vanilla,
 * asi que van como switch explicito aqui abajo (misma razon que en el mod: un switch
 * no se rompe si la libreria mueve el metodo de sitio).
 */
public final class PathBuilder {

    private PathBuilder() {}

    static long key(int x, int z) { return (((long) x) & 0xffffffffL) | ((((long) z) & 0xffffffffL) << 32); }
    static int kx(long k) { return (int) k; }
    static int kz(long k) { return (int) (k >>> 32); }

    public static void build(World w, Village v, House house, int doorX, int doorZ, int doorY, String facing) {
        ensureTrunks(w, v);
        // Si la puerta ya tiene un camino cerca, no hace falta trazar ramal.
        if (pathNear(v, doorX, doorZ, Cfg.PATH_SKIP_DIST)) return;
        List<Long> route = astar(w, v, doorX, doorZ);
        if (route == null || route.isEmpty()) route = straightFallback(v, doorX, doorZ);
        else route = straighten(w, v, route);
        layRoute(w, v, house, route, doorY);
        placeLamps(w, v, house, route);
        placeTrees(w, v, house, route);
    }

    /** true si hay alguna celda de camino a <= dist (Chebyshev) de (x,z). */
    private static boolean pathNear(Village v, int x, int z, int dist) {
        for (int dx = -dist; dx <= dist; dx++)
            for (int dz = -dist; dz <= dist; dz++)
                if (v.pathCells.contains(key(x + dx, z + dz))) return true;
        return false;
    }

    /**
     * Traza las 4 avenidas troncales desde los bordes de la plaza, si no hay red aun.
     * Sus bloques no pertenecen a ninguna casa (no se restauran al quitar casas).
     */
    public static void ensureTrunks(World w, Village v) {
        if (!v.pathCells.isEmpty()) return;
        House system = new House("system", "system", v.plazaX, v.plazaY, v.plazaZ, "north");
        // Arranca EN el borde de la plaza, no una celda mas alla: las puntas del rombo de
        // japonplaza1 llegan a distancia 3 y la caja mide 4, asi que con +1 quedaba una
        // franja de cesped de 1 bloque entre la plaza y la avenida.
        int edge = v.halfPlaza();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            List<Long> cells = new ArrayList<>();
            for (int s = 0; s < Cfg.TRUNK_LEN; s++) {
                int x = v.plazaX + d[0] * (edge + s);
                int z = v.plazaZ + d[1] * (edge + s);
                if (blockedByHouse(v, x, z)) break;
                cells.add(key(x, z));
            }
            if (!cells.isEmpty()) {
                layRoute(w, v, system, cells, v.plazaY);
                // Los arboles tambien van en las avenidas. Si solo se llamara a placeTrees()
                // desde build(), o sea SOLO sobre el ramal de una casa, la columna vertebral
                // del pueblo se quedaria pelada: un ramal casi nunca llega a TREE_SPACING/2.
                placeTrees(w, v, system, cells);
            }
        }
        // NOTA: placeLamps() NO se llama aqui, igual que en el mod. Las avenidas se quedan
        // sin faroles; esta pendiente alli y se mantiene igual para no cambiar el resultado
        // visual respecto al mod sin decidirlo a proposito.
    }

    // ---------------- A* ----------------

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Estado del A* = (x, z, direccion de llegada), empaquetado en un long. */
    private static long skey(int x, int z, int dir) {
        long cx = (x + 30000000L) & 0x3FFFFFFL;
        long cz = (z + 30000000L) & 0x3FFFFFFL;
        return cx | (cz << 26) | (((long) (dir + 1)) << 52);
    }
    private static int sX(long k) { return (int) ((k & 0x3FFFFFFL) - 30000000L); }
    private static int sZ(long k) { return (int) (((k >>> 26) & 0x3FFFFFFL) - 30000000L); }
    private static int sDir(long k) { return (int) ((k >>> 52) & 7L) - 1; }

    /**
     * A* con COSTES (no solo distancia):
     *  - pisar camino existente es casi gratis  -> los ramales se FUNDEN con la red
     *  - subir/bajar penaliza                   -> prefiere terreno plano (menos escaleras)
     *  - ir pegado a un camino sin unirse penaliza -> evita caminos paralelos duplicados
     */
    private static List<Long> astar(World w, Village v, int sx, int sz) {
        final int gx = v.plazaX, gz = v.plazaZ;
        PriorityQueue<long[]> open = new PriorityQueue<>(Comparator.comparingLong(a -> a[1]));
        Map<Long, Integer> g = new HashMap<>();
        Map<Long, Long> came = new HashMap<>();
        Map<Long, Integer> hCache = new HashMap<>();

        long start = skey(sx, sz, -1);
        g.put(start, 0);
        open.add(new long[]{start, manhattan(sx, sz, gx, gz) * (long) Cfg.COST_PATH_REUSE, 0});

        int expanded = 0;
        while (!open.isEmpty()) {
            long[] cur = open.poll();
            long ck = cur[0];
            int cg = g.getOrDefault(ck, Integer.MAX_VALUE);
            if (cur[2] > cg) continue;                   // entrada obsoleta en la cola
            int cx = sX(ck), cz = sZ(ck), cdir = sDir(ck);
            if (cdir >= 0 && isGoal(v, cx, cz)) return reconstruct(came, ck);
            if (++expanded > Cfg.ASTAR_MAX_NODES) break; // tope -> fallback
            int cy = groundCached(w, hCache, cx, cz);
            for (int d = 0; d < 4; d++) {
                int nx = cx + DIRS[d][0], nz = cz + DIRS[d][1];
                if (blocked(v, nx, nz)) continue;

                int cost;
                if (v.pathCells.contains(key(nx, nz))) {
                    cost = Cfg.COST_PATH_REUSE;          // FUSION: reutilizar camino existente
                } else {
                    cost = Cfg.COST_STEP;
                    int ny = groundCached(w, hCache, nx, nz);
                    cost += Math.abs(ny - cy) * Cfg.COST_SLOPE;
                    if (touchesPath(v, nx, nz)) cost += Cfg.COST_PARALLEL;
                }
                // CLAVE: cambiar de direccion cuesta. Sin esto, todas las rutas en "escalera"
                // valen lo mismo que la recta y el A* elegia una cualquiera -> camino dentado.
                if (cdir >= 0 && d != cdir) cost += Cfg.COST_TURN;

                long nk = skey(nx, nz, d);
                int ng = cg + cost;
                if (ng < g.getOrDefault(nk, Integer.MAX_VALUE)) {
                    g.put(nk, ng);
                    came.put(nk, ck);
                    long h = manhattan(nx, nz, gx, gz) * (long) Cfg.COST_PATH_REUSE;
                    open.add(new long[]{nk, ng + h, ng});
                }
            }
        }
        return null;
    }

    // ---------------- ENDEREZADO ----------------

    /**
     * El A* deja las rutas casi rectas, pero cuando hay que corregir 1 celda de lado el par
     * de giros cuesta lo MISMO en cualquier punto del tramo, asi que lo colocaba a mitad de
     * la recta (el "desvio" feo). Esta pasada rehace la ruta a base de codos: desde cada
     * punto busca el mas lejano al que se llega con un solo codo libre y salta hasta alli.
     */
    private static List<Long> straighten(World w, Village v, List<Long> route) {
        if (route.size() < 4) return route;
        Map<Long, Integer> hCache = new HashMap<>();
        List<Long> out = new ArrayList<>();
        out.add(route.get(0));
        int i = 0;
        while (i < route.size() - 1) {
            boolean preferX = kx(route.get(i + 1)) != kx(route.get(i));
            int found = -1;
            List<Long> seg = null;
            for (int j = route.size() - 1; j > i + 1 && found < 0; j--) {
                int origSlope = slopeOf(w, hCache, route, i, j);
                for (int t = 0; t < 2 && found < 0; t++) {
                    List<Long> cand = elbow(v, route.get(i), route.get(j), (t == 0) == preferX);
                    if (cand == null) continue;
                    if (cand.size() > j - i) continue;                      // nunca alargar
                    if (slopeOfCells(w, hCache, route.get(i), cand) > origSlope + Cfg.STRAIGHTEN_SLOPE_TOL)
                        continue;                                            // no cortar por una cuesta peor
                    found = j; seg = cand;
                }
            }
            if (found < 0) { i++; out.add(route.get(i)); continue; }
            out.addAll(seg);
            i = found;
        }
        return out;
    }

    /** Camino en L entre a y b (primero un eje, luego el otro). null si algo lo bloquea. */
    private static List<Long> elbow(Village v, long a, long b, boolean xFirst) {
        int cx = kx(a), cz = kz(a);
        final int bx = kx(b), bz = kz(b);
        List<Long> seg = new ArrayList<>();
        for (int leg = 0; leg < 2; leg++) {
            boolean doX = (leg == 0) == xFirst;
            if (doX) while (cx != bx) { cx += Integer.signum(bx - cx); if (blocked(v, cx, cz)) return null; seg.add(key(cx, cz)); }
            else     while (cz != bz) { cz += Integer.signum(bz - cz); if (blocked(v, cx, cz)) return null; seg.add(key(cx, cz)); }
        }
        return seg;
    }

    private static int slopeOf(World w, Map<Long, Integer> c, List<Long> route, int i, int j) {
        int sum = 0;
        for (int k = i; k < j; k++)
            sum += Math.abs(groundCached(w, c, kx(route.get(k + 1)), kz(route.get(k + 1)))
                          - groundCached(w, c, kx(route.get(k)), kz(route.get(k))));
        return sum;
    }

    private static int slopeOfCells(World w, Map<Long, Integer> c, long from, List<Long> cells) {
        int sum = 0, py = groundCached(w, c, kx(from), kz(from));
        for (long k : cells) {
            int y = groundCached(w, c, kx(k), kz(k));
            sum += Math.abs(y - py);
            py = y;
        }
        return sum;
    }

    /** Altura del suelo con cache (el A* consulta muchas veces las mismas celdas). */
    private static int groundCached(World w, Map<Long, Integer> cache, int x, int z) {
        return cache.computeIfAbsent(key(x, z), k -> VillageEngine.groundTopY(w, x, z));
    }

    /** true si la celda NO es camino pero tiene un camino justo al lado. */
    private static boolean touchesPath(Village v, int x, int z) {
        if (v.pathCells.contains(key(x, z))) return false;
        return v.pathCells.contains(key(x + 1, z)) || v.pathCells.contains(key(x - 1, z))
            || v.pathCells.contains(key(x, z + 1)) || v.pathCells.contains(key(x, z - 1));
    }

    /** Objetivo del A*: SOLO la red existente. La plaza no es objetivo: los ramales mueren
     *  en las avenidas, no en el centro. */
    private static boolean isGoal(Village v, int x, int z) {
        return v.pathCells.contains(key(x, z));
    }

    private static boolean blocked(Village v, int x, int z) {
        if (v.lampCells.contains(key(x, z))) return true;
        if (v.treeCells.contains(key(x, z))) return true;
        if (Math.abs(x - v.plazaX) <= v.halfPlaza() && Math.abs(z - v.plazaZ) <= v.halfPlaza())
            return true; // no atravesar la plaza
        return blockedByHouse(v, x, z);
    }

    private static boolean blockedByHouse(Village v, int x, int z) {
        for (House h : v.houses)
            if (Math.abs(x - h.x) <= h.halfX() + Cfg.PATH_MARGIN
                    && Math.abs(z - h.z) <= h.halfZ() + Cfg.PATH_MARGIN) return true;
        return false;
    }

    private static boolean insideAnyHouse(Village v, int x, int z) {
        for (House h : v.houses)
            if (Math.abs(x - h.x) <= h.halfX() && Math.abs(z - h.z) <= h.halfZ()) return true;
        return false;
    }

    private static int manhattan(int x, int z, int gx, int gz) { return Math.abs(x - gx) + Math.abs(z - gz); }

    private static List<Long> reconstruct(Map<Long, Long> came, long end) {
        LinkedList<Long> path = new LinkedList<>();
        Long cur = end;
        while (cur != null) { path.addFirst(key(sX(cur), sZ(cur))); cur = came.get(cur); }
        return path;
    }

    private static List<Long> straightFallback(Village v, int sx, int sz) {
        List<Long> path = new ArrayList<>();
        int px = sx, pz = sz, guard = 0;
        while ((Math.abs(px - v.plazaX) > v.halfPlaza() || Math.abs(pz - v.plazaZ) > v.halfPlaza()) && guard++ < 3000) {
            path.add(key(px, pz));
            if (Math.abs(px - v.plazaX) > v.halfPlaza()) px += (px < v.plazaX) ? 1 : -1;
            else if (Math.abs(pz - v.plazaZ) > v.halfPlaza()) pz += (pz < v.plazaZ) ? 1 : -1;
            else break;
        }
        return path;
    }

    // ---------------- construir la ruta ----------------

    private static void layRoute(World w, Village v, House house, List<Long> route, int startY) {
        int n = route.size();
        if (n == 0) return;

        // ---- Pase 1: altura del camino en cada celda del NUCLEO ----
        int[] ys = new int[n];
        int pathY = startY;
        for (int i = 0; i < n; i++) {
            int x = kx(route.get(i)), z = kz(route.get(i));
            // Los 2 primeros bloques quedan clavados a la altura de la puerta.
            // En agua no se sigue el terreno: el puente mantiene el nivel actual.
            if (i >= 2 && !isWaterCell(w, x, z)) {
                int desired = VillageEngine.groundTopY(w, x, z);
                int diff = desired - pathY;
                if (diff >= 2) pathY++;            // pared alta: sube 1 (terraza transitable)
                else if (diff <= -2) pathY--;      // caida alta: baja 1
                else {                              // desnivel de 1: ignora baches cortos
                    int aheadIdx = Math.min(i + Cfg.PATH_SMOOTH, n - 1);
                    int aheadGround = VillageEngine.groundTopY(w, kx(route.get(aheadIdx)), kz(route.get(aheadIdx)));
                    if (diff == 1 && aheadGround >= pathY + 1) pathY++;
                    else if (diff == -1 && aheadGround <= pathY - 1) pathY--;
                }
            }
            ys[i] = pathY;
        }

        // ---- Pase 2: decidir TODAS las celdas (nucleo + ensanche + esquinas) ----
        // El nucleo va primero: si una celda de ensanche coincide con el nucleo, manda el nucleo.
        LinkedHashMap<Long, Integer> place = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) place.putIfAbsent(route.get(i), ys[i]);

        BlockFace[] sides = new BlockFace[n];
        for (int i = 0; i < n; i++) {
            int x = kx(route.get(i)), z = kz(route.get(i));
            BlockFace side = fixedSide(travelAt(route, i));
            int ax = x + side.getModX(), az = z + side.getModZ();
            if (!canThicken(v, ax, az)) {
                BlockFace alt = side.getOppositeFace();
                int bx = x + alt.getModX(), bz = z + alt.getModZ();
                if (!canThicken(v, bx, bz)) continue;   // sin sitio: aqui el camino va de ancho 1
                side = alt; ax = bx; az = bz;
            }
            sides[i] = side;
            place.putIfAbsent(key(ax, az), ys[i]);
        }

        // Relleno de esquinas: en cada giro, la celda del giro solo llevaba el ensanche del
        // tramo SALIENTE. Se le anade tambien el del ENTRANTE (+ la diagonal), de forma que
        // la esquina queda como un 2x2 macizo en vez de un escalon mordido.
        for (int i = 1; i + 1 < n; i++) {
            if (sides[i] == null || sides[i - 1] == null) continue;
            if (dirBetween(route.get(i - 1), route.get(i)) == dirBetween(route.get(i), route.get(i + 1))) continue;
            BlockFace sIn = sides[i - 1], sOut = sides[i];
            if (mismoEje(sIn, sOut)) continue;
            int x = kx(route.get(i)), z = kz(route.get(i));
            addCell(place, v, x + sIn.getModX(), z + sIn.getModZ(), ys[i]);
            addCell(place, v, x + sIn.getModX() + sOut.getModX(), z + sIn.getModZ() + sOut.getModZ(), ys[i]);
        }

        // ---- Pase 3: construir ----
        // Se registran TODAS las celdas pavimentadas (eje + ensanche + esquinas), no solo el eje.
        // Si solo se registra el eje, el grafo cree que la calle mide 1 de ancho: un ramal nuevo
        // empalma contra el eje pisando el ensanche del viejo y la union queda descuadrada.
        for (Map.Entry<Long, Integer> e : place.entrySet()) {
            int x = kx(e.getKey()), z = kz(e.getKey()), y = e.getValue();
            if (isWaterCell(w, x, z)) layBridge(w, v, house, x, y, z);   // puente: no rellena debajo
            else layFlat(w, v, house, x, y, z);
            v.pathCells.add(e.getKey());
        }
    }

    private static void addCell(Map<Long, Integer> place, Village v, int x, int z, int y) {
        if (place.containsKey(key(x, z))) return;
        if (!canThicken(v, x, z)) return;
        place.put(key(x, z), y);
    }

    private static BlockFace travelAt(List<Long> route, int i) {
        if (i + 1 < route.size()) return dirBetween(route.get(i), route.get(i + 1));
        if (i > 0) return dirBetween(route.get(i - 1), route.get(i));
        return BlockFace.SOUTH;
    }

    private static BlockFace dirBetween(long a, long b) {
        return dirOf(kx(b) - kx(a), kz(b) - kz(a));
    }

    /** Lado FIJO de ensanche: tramos E/O engordan al sur, tramos N/S engordan al este.
     *  Al ser una regla global (y no "a la derecha del viaje"), dos tramos seguidos nunca
     *  saltan de lado: era la otra causa del borde dentado. */
    private static BlockFace fixedSide(BlockFace travel) {
        return ejeX(travel) ? BlockFace.SOUTH : BlockFace.EAST;
    }

    private static boolean ejeX(BlockFace f) { return f == BlockFace.EAST || f == BlockFace.WEST; }
    private static boolean mismoEje(BlockFace a, BlockFace b) { return ejeX(a) == ejeX(b); }

    private static boolean canThicken(Village v, int x, int z) {
        if (v.pathCells.contains(key(x, z))) return false;
        if (blocked(v, x, z)) return false;
        return true;
    }

    /** true si la superficie de esta columna es agua/lava. */
    private static boolean isWaterCell(World w, int x, int z) {
        int top = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        Block b = w.getBlockAt(x, top, z);
        if (b.isLiquid()) return true;
        BlockData d = b.getBlockData();
        return d instanceof Waterlogged wl && wl.isWaterlogged();
    }

    /**
     * Celda ocupada por un farol: nunca se despeja al trazar caminos.
     *
     * Se mira la CELDA y no el TIPO de bloque. Mirar el tipo (valla de roble oscuro, losa)
     * dejo de valer al pasar a postes de estructura: el poste japones es de abedul y el
     * despeje lo habria decapitado. Por celda vale para cualquier skin.
     */
    private static boolean isLampCell(Village v, int x, int z) {
        return v.lampCells.contains(key(x, z));
    }

    private static boolean isLampBlock(Block b) {
        Material m = b.getType();
        return m == Cfg.LAMP_POLE || m == Cfg.LAMP_ARM || m == Cfg.LAMP_BASE || m == Material.LANTERN;
    }

    /** Puente: losa del camino al nivel dado y despeje arriba, SIN rellenar debajo. */
    private static void layBridge(World w, Village v, House house, int x, int y, int z) {
        BlockData air = Material.AIR.createBlockData();
        set(w, house, x, y, z, Cfg.PATH_BLOCK.createBlockData());
        if (isLampCell(v, x, z)) return;
        for (int dy = 1; dy <= 3; dy++) {
            if (isLampBlock(w.getBlockAt(x, y + dy, z))) continue;
            set(w, house, x, y + dy, z, air);
        }
    }

    private static void layFlat(World w, Village v, House house, int x, int y, int z) {
        prepColumn(w, v, house, x, y, z);
        set(w, house, x, y, z, Cfg.PATH_BLOCK.createBlockData());
    }

    /** Despeja arriba y rellena abajo: solido para escalones, valla solo si es profundo. */
    private static void prepColumn(World w, Village v, House house, int x, int y, int z) {
        BlockData air = Material.AIR.createBlockData();
        if (!isLampCell(v, x, z))
            for (int dy = 1; dy <= 3; dy++) {
                if (isLampBlock(w.getBlockAt(x, y + dy, z))) continue; // no decapitar postes
                set(w, house, x, y + dy, z, air);
            }
        int yy = y - 1, depth = 0;
        while (depth < Cfg.MAX_FILL_DOWN) {
            Block b = w.getBlockAt(x, yy, z);
            if (!b.getType().isAir() && !b.isLiquid() && !b.getBlockData().isReplaceable()) break;
            depth++;
            BlockData fill = (depth <= Cfg.SHALLOW_FILL)
                    ? Cfg.PATH_SUPPORT.createBlockData()        // escalon normal: solido
                    : Cfg.PATH_SUPPORT_DEEP.createBlockData();  // caida profunda: pilote de valla
            set(w, house, x, yy, z, fill);
            yy--;
        }
    }

    // ---------------- FAROLES ----------------

    private static void placeLamps(World w, Village v, House house, List<Long> route) {
        int since = 0;
        boolean flip = false;
        for (int i = 1; i < route.size(); i++) {
            since++;
            if (since < Cfg.LAMP_SPACING) continue;
            long cell = route.get(i);
            int x = kx(cell), z = kz(cell);
            BlockFace travel = dirOf(x - kx(route.get(i - 1)), z - kz(route.get(i - 1)));
            BlockFace side = flip ? horario(travel) : antihorario(travel);
            int px = x + side.getModX() * Cfg.LAMP_SIDE_DIST;
            int pz = z + side.getModZ() * Cfg.LAMP_SIDE_DIST;
            int anchorY = surfacePathY(w, x, z);
            if (tryPlacePost(w, v, house, px, pz, side.getOppositeFace(), anchorY)) flip = !flip;
            since = 0; // colocado o no, se reinicia el contador (no reintentar = no amontonar)
        }
    }

    private static boolean tryPlacePost(World w, Village v, House house, int px, int pz,
                                        BlockFace armDir, int anchorY) {
        long k = key(px, pz);
        if (insideAnyHouse(v, px, pz)) return false;
        if (v.pathCells.contains(k)) return false;
        // no plantar postes en la plaza ni pegados a ella (cortarian el camino principal)
        if (Math.abs(px - v.plazaX) <= v.halfPlaza() + 2 && Math.abs(pz - v.plazaZ) <= v.halfPlaza() + 2) return false;
        if (isWaterCell(w, px, pz)) return false;
        if (nearExistingLamp(v, px, pz)) return false;
        int sideGround = VillageEngine.groundTopY(w, px, pz);
        if (sideGround > anchorY + 1) return false;           // el lado es mas alto que el camino
        buildPost(w, v, house, px, anchorY, pz, armDir, sideGround);
        v.lampCells.add(k);
        // El brazo vuela sobre la celda de al lado: tambien hay que protegerla, o el
        // despeje del camino le arranca el farol.
        v.lampCells.add(key(px + armDir.getModX(), pz + armDir.getModZ()));
        return true;
    }

    // ---------------- ARBOLES ----------------

    /**
     * Arboles decorativos junto al camino. Mismo mecanismo que los faroles, con tres
     * diferencias a proposito:
     *   - TREE_SPACING es mas del doble que LAMP_SPACING: decoran, no iluminan.
     *   - se exige TREE_LAMP_GAP a cualquier poste, para que no se tapen entre si.
     *   - se recorre la ruta con un desfase (TREE_SPACING / 2) para no arrancar en la misma
     *     celda que el primer farol, que era la forma facil de que salieran pegados.
     */
    private static void placeTrees(World w, Village v, House house, List<Long> route) {
        if (!Cfg.PLANTAR_ARBOLES) return;
        Skins.Skin skin = Skins.skin(v.skin == null ? Cfg.DEFAULT_SKIN : v.skin);
        if (skin == null || skin.tree == null) return;
        Structure tpl = Structures.cargar(skin.tree);
        Structures.Info info = Structures.info(skin.tree);
        if (tpl == null || info == null) return;

        int since = Cfg.TREE_SPACING / 2;   // desfase respecto a los faroles
        boolean flip = true;                // arranca en el lado contrario al primer farol
        for (int i = 1; i < route.size(); i++) {
            since++;
            if (since < Cfg.TREE_SPACING) continue;
            long cell = route.get(i);
            int x = kx(cell), z = kz(cell);
            BlockFace travel = dirOf(x - kx(route.get(i - 1)), z - kz(route.get(i - 1)));
            BlockFace side = flip ? horario(travel) : antihorario(travel);
            int px = x + side.getModX() * Cfg.TREE_SIDE_DIST;
            int pz = z + side.getModZ() * Cfg.TREE_SIDE_DIST;
            int anchorY = surfacePathY(w, x, z);
            if (tryPlaceTree(w, v, house, tpl, info, px, pz, anchorY)) flip = !flip;
            since = 0;
        }
    }

    private static boolean tryPlaceTree(World w, Village v, House house, Structure tpl,
                                        Structures.Info info, int px, int pz, int anchorY) {
        int fw = info.sizeX, fd = info.sizeZ, fh = info.sizeY;
        int minX = px - fw / 2, minZ = pz - fd / 2;

        // Toda la huella tiene que estar libre: nada de plantar medio arbol sobre el camino,
        // una casa, la plaza o un poste.
        for (int dx = 0; dx < fw; dx++) {
            for (int dz = 0; dz < fd; dz++) {
                int x = minX + dx, z = minZ + dz;
                long k = key(x, z);
                if (v.pathCells.contains(k)) return false;
                if (v.lampCells.contains(k)) return false;
                if (v.treeCells.contains(k)) return false;
                if (insideAnyHouse(v, x, z)) return false;
                if (isWaterCell(w, x, z)) return false;
                if (Math.abs(x - v.plazaX) <= v.halfPlaza() + 2
                        && Math.abs(z - v.plazaZ) <= v.halfPlaza() + 2) return false;
            }
        }
        if (nearExistingTree(v, px, pz)) return false;
        if (nearExistingLampForTree(v, px, pz)) return false;

        // El terreno bajo el arbol tiene que ser parejo: en una cuesta quedaria flotando o
        // enterrado. Se compara con la altura del camino, que es la referencia visible.
        int suelo = VillageEngine.groundTopY(w, px, pz);
        if (Math.abs(suelo - anchorY) > 1) return false;

        int baseY = suelo + 1;
        int[] origin = Structures.originFor(StructureRotation.NONE, fw, fd, minX, minZ);
        Structures.paste(w, house, tpl, StructureRotation.NONE, origin[0], origin[1],
                         minX, baseY, minZ, fw, fh, fd);

        // Reservar TODA la huella: asi ningun camino futuro la parte por la mitad.
        for (int dx = 0; dx < fw; dx++)
            for (int dz = 0; dz < fd; dz++)
                v.treeCells.add(key(minX + dx, minZ + dz));
        return true;
    }

    private static boolean nearExistingTree(Village v, int x, int z) {
        for (long k : v.treeCells)
            if (Math.abs(kx(k) - x) < Cfg.TREE_MIN_GAP && Math.abs(kz(k) - z) < Cfg.TREE_MIN_GAP) return true;
        return false;
    }

    /** Un arbol nunca se planta pegado a un poste: se taparian el uno al otro. */
    private static boolean nearExistingLampForTree(Village v, int x, int z) {
        for (long k : v.lampCells)
            if (Math.abs(kx(k) - x) < Cfg.TREE_LAMP_GAP && Math.abs(kz(k) - z) < Cfg.TREE_LAMP_GAP) return true;
        return false;
    }

    /** Altura de la losa del camino en (x,z): busca el bloque de camino cerca de la superficie. */
    private static int surfacePathY(World w, int x, int z) {
        int top = VillageEngine.groundTopY(w, x, z) + 4;
        for (int y = top; y > top - 12; y--)
            if (w.getBlockAt(x, y, z).getType() == Cfg.PATH_BLOCK) return y;
        return VillageEngine.groundTopY(w, x, z);
    }

    private static boolean nearExistingLamp(Village v, int x, int z) {
        for (long k : v.lampCells)
            if (Math.abs(kx(k) - x) < Cfg.LAMP_MIN_GAP && Math.abs(kz(k) - z) < Cfg.LAMP_MIN_GAP) return true;
        return false;
    }

    /**
     * Poste de farol. Si la skin trae estructura (japonposte1) se pega esa; si no, el poste
     * procedural.
     *
     * El anclaje NO se codifica a mano: Structures.Info deduce cual es la columna del mastil
     * (la que se apoya en y=0) y hacia donde sale el brazo (donde cuelga el farol). Se pega
     * de forma que el mastil caiga exactamente en (px,pz) y el brazo apunte a la calle.
     */
    private static void buildPost(World w, Village v, House house, int px, int baseY, int pz,
                                  BlockFace armDir, int sideGround) {
        if (buildPostFromStructure(w, v, house, px, baseY, pz, armDir, sideGround)) return;
        buildPostProcedural(w, house, px, baseY, pz, armDir, sideGround);
    }

    private static boolean buildPostFromStructure(World w, Village v, House house, int px, int baseY,
                                                  int pz, BlockFace armDir, int sideGround) {
        Skins.Skin skin = Skins.skin(v.skin == null ? Cfg.DEFAULT_SKIN : v.skin);
        if (skin == null) return false;
        Structures.Info info = Structures.info(skin.lamp);
        Structure tpl = Structures.cargar(skin.lamp);
        if (info == null || tpl == null) return false;

        StructureRotation rot = (info.lampFront == null) ? StructureRotation.NONE
                              : Structures.rotationFor(info.lampFront, armDir);
        // origen tal que la columna del mastil caiga justo en (px,pz)
        int[] t = Structures.rotXZ(info.lampX, info.lampZ, rot);
        int originX = px - t[0], originZ = pz - t[1];
        int fw = Structures.footprintX(rot, info.sizeX, info.sizeZ);
        int fd = Structures.footprintZ(rot, info.sizeX, info.sizeZ);
        int minX = Structures.minXOf(rot, info.sizeX, info.sizeZ, originX);
        int minZ = Structures.minZOf(rot, info.sizeX, info.sizeZ, originZ);

        // La skin puede pedir que sus postes se asienten N bloques mas arriba, igual que las
        // casas. El pilar de abajo rellena el hueco que deja.
        int postY = baseY + skin.lift;

        // Foto de la capa que pisa el poste ANTES de pegar. El .nbt del poste tiene AIRE en la
        // columna del brazo (el farol vuela), y pegar ese aire borraba el bloque de camino que
        // hubiera debajo: por eso salia un hueco justo delante del poste.
        BlockData[] antes = new BlockData[fw * fd];
        for (int dx = 0; dx < fw; dx++)
            for (int dz = 0; dz < fd; dz++)
                antes[dx * fd + dz] = w.getBlockAt(minX + dx, postY, minZ + dz).getBlockData();

        Structures.paste(w, house, tpl, rot, originX, originZ, minX, postY, minZ, fw, info.sizeY, fd);

        // Devolver lo que habia en las celdas que el poste dejo en aire (el camino, el cesped).
        for (int dx = 0; dx < fw; dx++)
            for (int dz = 0; dz < fd; dz++) {
                Block b = w.getBlockAt(minX + dx, postY, minZ + dz);
                BlockData prev = antes[dx * fd + dz];
                if (b.getType().isAir() && !prev.getMaterial().isAir())
                    set(w, house, minX + dx, postY, minZ + dz, prev);
            }

        // Pilar si el poste queda flotando: se prolonga hacia abajo el propio bloque de la base
        // del mastil, asi funciona con cualquier skin sin saber de que esta hecha.
        BlockData foot = w.getBlockAt(px, postY, pz).getBlockData();
        if (!foot.getMaterial().isAir())
            for (int y = postY - 1; y >= sideGround && y > postY - 8; y--)
                set(w, house, px, y, pz, foot);
        return true;
    }

    private static void buildPostProcedural(World w, House house, int px, int baseY, int pz,
                                            BlockFace armDir, int sideGround) {
        BlockData base = Cfg.LAMP_BASE.createBlockData();
        BlockData pole = Cfg.LAMP_POLE.createBlockData();
        BlockData arm  = Cfg.LAMP_ARM.createBlockData();
        if (arm instanceof Slab slab) slab.setType(Slab.Type.BOTTOM);
        BlockData lantern = Material.LANTERN.createBlockData();
        if (lantern instanceof Lantern l) l.setHanging(true);

        set(w, house, px, baseY, pz, base);
        // si el suelo del lado esta por debajo, baja un pilar para que no flote
        for (int y = baseY - 1; y >= sideGround && y > baseY - 8; y--) set(w, house, px, y, pz, base);
        for (int i = 1; i <= Cfg.LAMP_POLE_HEIGHT; i++) set(w, house, px, baseY + i, pz, pole);

        int armY = baseY + Cfg.LAMP_POLE_HEIGHT + 1;
        set(w, house, px, armY, pz, arm);
        int ax = px, az = pz;
        for (int step = 1; step <= 2; step++) {
            ax = px + armDir.getModX() * step;
            az = pz + armDir.getModZ() * step;
            set(w, house, ax, armY, az, arm);
        }
        set(w, house, ax, armY - 1, az, lantern);
    }

    private static BlockFace dirOf(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Giro de 90 grados. BlockFace no trae getClockWise(): switch explicito. */
    private static BlockFace horario(BlockFace f) {
        switch (f) {
            case NORTH: return BlockFace.EAST;
            case EAST:  return BlockFace.SOUTH;
            case SOUTH: return BlockFace.WEST;
            default:    return BlockFace.NORTH;
        }
    }

    private static BlockFace antihorario(BlockFace f) {
        switch (f) {
            case NORTH: return BlockFace.WEST;
            case WEST:  return BlockFace.SOUTH;
            case SOUTH: return BlockFace.EAST;
            default:    return BlockFace.NORTH;
        }
    }

    private static void set(World w, House house, int x, int y, int z, BlockData data) {
        Block b = w.getBlockAt(x, y, z);
        BlockData old = b.getBlockData();
        if (old.matches(data)) return;
        house.recordChange(x, y, z, old.getAsString());
        b.setBlockData(data, false);
    }
}
