package com.vutocorp.livevillage;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunks forzados de un pueblo entero (/lv village forceload). Port parcial de
 * Chunks.java del mod: solo el modo MANUAL (apply/countFor). El Guard automatico que usa
 * el mod durante build() no se porta: ese existe para el vaiven cargar/construir/soltar
 * de NeoForge y para que addFreshEntity no deje al aldeano en cola; Bukkit ya carga el
 * chunk de forma sincrona al escribir un bloque o hacer spawn(), asi que no hace falta.
 */
public final class Chunks {

    private Chunks() {}

    private static long key(int cx, int cz) {
        return (((long) cx) & 0xffffffffL) | ((((long) cz) & 0xffffffffL) << 32);
    }
    private static int cx(long k) { return (int) k; }
    private static int cz(long k) { return (int) (k >>> 32); }

    public static List<Long> chunksOfBox(int x1, int z1, int x2, int z2, int margin) {
        int minX = Math.min(x1, x2) - margin, maxX = Math.max(x1, x2) + margin;
        int minZ = Math.min(z1, z2) - margin, maxZ = Math.max(z1, z2) + margin;
        List<Long> out = new ArrayList<>();
        for (int c = minX >> 4; c <= (maxX >> 4); c++)
            for (int d = minZ >> 4; d <= (maxZ >> 4); d++)
                out.add(key(c, d));
        return out;
    }

    /** Caja que cubre plaza + todas las casas + todos los caminos del pueblo. */
    public static int[] villageBox(Village v) {
        int minX = v.plazaX, maxX = v.plazaX, minZ = v.plazaZ, maxZ = v.plazaZ;
        for (House h : v.houses) {
            minX = Math.min(minX, h.x - h.halfX()); maxX = Math.max(maxX, h.x + h.halfX());
            minZ = Math.min(minZ, h.z - h.halfZ()); maxZ = Math.max(maxZ, h.z + h.halfZ());
        }
        for (long k : v.pathCells) {
            int x = (int) k, z = (int) (k >>> 32);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    /** Cuantos chunks ocuparia fijar el pueblo entero. Para avisar ANTES de fijarlo. */
    public static int countFor(Village v) {
        int[] b = villageBox(v);
        return chunksOfBox(b[0], b[1], b[2], b[3], Cfg.FORCE_MARGIN).size();
    }

    /**
     * Fija (o suelta) los chunks del pueblo. Devuelve cuantos quedan fijados.
     * Si el pueblo no cabe en el tope, fija los MAS CERCANOS A LA PLAZA hasta llenarlo.
     */
    public static int apply(World w, Village v, boolean force) {
        int[] b = villageBox(v);
        List<Long> all = chunksOfBox(b[0], b[1], b[2], b[3], Cfg.FORCE_MARGIN);
        if (!force) {
            int n = 0;
            for (long k : all) {
                if (w.isChunkForceLoaded(cx(k), cz(k))) {
                    w.setChunkForceLoaded(cx(k), cz(k), false);
                    n++;
                }
            }
            return n;
        }
        final int pcx = v.plazaX >> 4, pcz = v.plazaZ >> 4;
        if (all.size() > Cfg.MAX_FORCED_CHUNKS) {
            all.sort((k1, k2) -> Long.compare(dist2(k1, pcx, pcz), dist2(k2, pcx, pcz)));
            for (int i = Cfg.MAX_FORCED_CHUNKS; i < all.size(); i++)
                w.setChunkForceLoaded(cx(all.get(i)), cz(all.get(i)), false);
            all = all.subList(0, Cfg.MAX_FORCED_CHUNKS);
        }
        for (long k : all) w.setChunkForceLoaded(cx(k), cz(k), true);
        return all.size();
    }

    private static long dist2(long k, int pcx, int pcz) {
        long dx = cx(k) - pcx, dz = cz(k) - pcz;
        return dx * dx + dz * dz;
    }
}
