package com.vutocorp.livevillage;

import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Punto UNICO por donde entra una donacion. Port de Donaciones.java del mod.
 *
 * En el mod hay dos caminos hacia aqui (el puente de Python via RCON, y el cliente del
 * streamer via paquete de red) y los dos pasan por la MISMA validacion porque el
 * servidor no se fia de quien le habla. En el plugin ya no existe el segundo camino: la
 * conexion a TikTok la abre el propio servidor (TikTokManager), asi que ya no hay
 * "cliente que se pueda modificar" al que no haya que fiarle nada. Aun asi el ritmo y
 * los limites se quedan AQUI y no en TikTokManager, por la misma razon de fondo: es la
 * unica puerta de entrada, y que lo sea de verdad (no solo de intencion) es lo que
 * evita que una racha de regalos duplique el trabajo si algun dia hay un segundo
 * origen (el puente de Python, por ejemplo).
 */
public final class Donaciones {

    private Donaciones() {}

    public enum Estado { OK, SIN_PUEBLO, CERRADO, MODELO_DESCONOCIDO, SIN_SITIO, RITMO, TOPE }

    public static final class Resultado {
        public final Estado estado;
        public final String detalle;
        public final House casa;
        Resultado(Estado e, String d, House c) { estado = e; detalle = d; casa = c; }
        public boolean ok() { return estado == Estado.OK; }
    }

    private static Resultado fallo(Estado e, String d) { return new Resultado(e, d, null); }

    // ---------------- control de ritmo, por pueblo ----------------

    private static final Map<String, Deque<Long>> HISTORIAL = new HashMap<>();
    private static final Map<String, Long> ULTIMA = new HashMap<>();

    private static Resultado comprobarRitmo(Village v) {
        long ahora = System.currentTimeMillis();
        String clave = v.name.toLowerCase();

        long ultima = ULTIMA.getOrDefault(clave, 0L);
        if (Cfg.DONACION_COOLDOWN_MS > 0 && ahora - ultima < Cfg.DONACION_COOLDOWN_MS)
            return fallo(Estado.RITMO, "demasiado seguido; espera "
                + ((Cfg.DONACION_COOLDOWN_MS - (ahora - ultima)) / 1000 + 1) + " s");

        Deque<Long> h = HISTORIAL.computeIfAbsent(clave, k -> new ArrayDeque<>());
        while (!h.isEmpty() && h.peekFirst() < ahora - 60_000L) h.pollFirst();
        if (Cfg.DONACION_TOPE_MINUTO > 0 && h.size() >= Cfg.DONACION_TOPE_MINUTO)
            return fallo(Estado.RITMO, "tope de " + Cfg.DONACION_TOPE_MINUTO + " casas por minuto");

        if (Cfg.DONACION_TOPE_PUEBLO > 0 && v.houses.size() >= Cfg.DONACION_TOPE_PUEBLO)
            return fallo(Estado.TOPE, "el pueblo llego a su tope de " + Cfg.DONACION_TOPE_PUEBLO + " casas");
        return null;
    }

    private static void anotarRitmo(Village v) {
        long ahora = System.currentTimeMillis();
        String clave = v.name.toLowerCase();
        ULTIMA.put(clave, ahora);
        HISTORIAL.computeIfAbsent(clave, k -> new ArrayDeque<>()).addLast(ahora);
    }

    // ---------------- entrada por REGALO ----------------

    /**
     * Camino de donacion real: llega un regalo y el SERVIDOR decide que casa toca,
     * consultando la tabla de Regalos. Ni TikTok ni el puente eligen el modelo.
     */
    public static Resultado porRegalo(Village v, long giftId, String nombreRegalo, int monedas,
                                      String donadorRaw) {
        if (Regalos.registrando) Regalos.anotarVisto(giftId, nombreRegalo, monedas);

        // Un regalo puede dar mascota, casa, o las dos si esta en las dos tablas. La mascota
        // va primero porque no consume sitio ni cuenta para el ritmo: aunque el pueblo este
        // lleno o cerrado a casas nuevas, el donador se lleva su mob.
        String mob = Mascotas.mobPara(giftId, nombreRegalo, monedas);
        Mascotas.Resultado rm = null;
        if (mob != null) rm = Mascotas.soltar(v, mob, donadorRaw);

        String modelo = Regalos.modeloPara(giftId, nombreRegalo, monedas);
        if (modelo == null && rm != null && rm.ok)
            return new Resultado(Estado.OK, rm.detalle, rm.casa);
        if (modelo == null)
            return fallo(Estado.MODELO_DESCONOCIDO, "ninguna regla encaja con id " + giftId
                + (nombreRegalo == null || nombreRegalo.isEmpty() ? "" : " / '" + nombreRegalo + "'")
                + " / " + monedas + " monedas. Mira /lv gift list");
        return colocar(v, modelo, donadorRaw, false);
    }

    // ---------------- colocacion ----------------

    /**
     * Coloca una casa de donacion. Es la unica forma de crear casas por donacion.
     *
     * @param modelId    id del modelo, o null / "auto" para el mas barato de la skin
     * @param donadorRaw nombre tal cual llega de TikTok; se limpia aqui dentro
     * @param saltarRitmo true solo para comandos de administrador a mano
     */
    public static Resultado colocar(Village v, String modelId, String donadorRaw, boolean saltarRitmo) {
        if (v == null) return fallo(Estado.SIN_PUEBLO, "no hay pueblo");
        if (!v.open) return fallo(Estado.CERRADO, "el pueblo '" + v.name + "' esta cerrado a donadores");

        if (!saltarRitmo) {
            Resultado r = comprobarRitmo(v);
            if (r != null) return r;
        }

        World w = VillageEngine.worldOf(v);

        if ("auto".equalsIgnoreCase(modelId)) modelId = null;
        Skins.Model model = Skins.model(modelId);
        if (modelId != null && model == null)
            return fallo(Estado.MODELO_DESCONOCIDO, "no existe el modelo '" + modelId + "'");
        if (model == null) model = Skins.cheapest(v.skin == null ? Cfg.DEFAULT_SKIN : v.skin);

        // huella real del modelo: sin saber aun la rotacion se reserva la medida mayor
        int hx = Cfg.PLOT_HALF, hz = Cfg.PLOT_HALF;
        if (model != null) {
            Structures.Info info = Structures.info(model.structure);
            if (info != null) { hx = Math.max(info.sizeX, info.sizeZ) / 2; hz = hx; }
            else model = null;                                  // sin .nbt: cabaña provisional
        }

        int[] flat = VillageEngine.findSpot(w, v, hx, hz);
        if (flat == null)
            return fallo(Estado.SIN_SITIO, "no encontre sitio libre y seco en '" + v.name + "'");

        int groundY = VillageEngine.averageGroundY(w, flat[0], flat[1], hx, hz);

        String limpio = NameUtil.clean(donadorRaw);
        House h = new House(donadorRaw, limpio, flat[0], groundY, flat[1], "north");
        h.num = ++v.nextNum;
        if (model != null) h.modelId = model.id;
        v.houses.add(h);                       // registrar ANTES: asi su propio camino la respeta

        VillageEngine.buildHouse(w, v, h, flat[0], groundY, flat[1]);
        if (!saltarRitmo) anotarRitmo(v);
        VillageEngine.announce(v, h, v.houses.size());
        return new Resultado(Estado.OK, "casa #" + h.num + " de '" + limpio + "'", h);
    }
}
