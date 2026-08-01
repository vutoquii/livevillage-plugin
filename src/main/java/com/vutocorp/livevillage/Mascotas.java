package com.vutocorp.livevillage;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Mascotas de donador. Port de Mascotas.java del mod.
 *
 * El mob se liga al DONADOR, no a una casa concreta:
 *   1. Si el donador ya tiene casa, va a su primera casa con hueco (MOBS_POR_CASA).
 *   2. Si esa esta llena y tiene mas casas, pasa a la siguiente.
 *   3. Si todas estan llenas, se queda en la ultima (mejor amontonar que perder el regalo).
 *   4. Si todavia no tiene ninguna casa, sale en la PLAZA. Cuando le construyan casa,
 *      /lv mob recoger la lleva a su sitio.
 *
 * Diferencia con el mod: para crear la entidad y sortear su variante (color del gato,
 * marcas del caballo...) el mod necesita Compat.crearEntidad() con finalizeSpawn a mano.
 * World.spawn(location, Class) de Bukkit ya hace esa randomizacion por dentro, asi que
 * aqui no hace falta ningun equivalente de Compat.
 */
public final class Mascotas {

    private Mascotas() {}

    public static final String ARCHIVO = "livevillage-mascotas.json";

    // ---------------- regla ----------------

    /** Misma forma que Regalos.Regla a proposito: quien ya sabe configurar los regalos
     *  no tiene que aprender nada nuevo. Gana la mas especifica. */
    public static final class Regla {
        public long giftId;
        public String nombre;
        public int monedas;
        public int monedasMin;
        public int monedasMax;
        /** Id de entidad ("minecraft:cat", "minecraft:wolf"...). */
        public String mob;

        int especificidad() {
            if (giftId > 0) return 4;
            if (nombre != null && !nombre.isEmpty()) return 3;
            if (monedas > 0) return 2;
            return 1;
        }

        int anchoTramo() {
            int min = monedasMin > 0 ? monedasMin : 0;
            int max = monedasMax > 0 ? monedasMax : Integer.MAX_VALUE;
            return (max == Integer.MAX_VALUE) ? Integer.MAX_VALUE : (max - min);
        }

        public boolean encaja(long id, String nom, int coins) {
            if (giftId > 0) return giftId == id;
            if (nombre != null && !nombre.isEmpty()) return Regalos.norm(nombre).equals(Regalos.norm(nom));
            if (monedas > 0) return coins == monedas;
            if (monedasMin > 0 && coins < monedasMin) return false;
            if (monedasMax > 0 && coins > monedasMax) return false;
            return monedasMin > 0 || monedasMax > 0;
        }

        public String describe() {
            if (giftId > 0) return "id " + giftId;
            if (nombre != null && !nombre.isEmpty()) return "nombre '" + nombre + "'";
            if (monedas > 0) return "cualquiera de " + monedas + " monedas";
            String a = monedasMin > 0 ? String.valueOf(monedasMin) : "0";
            String b = monedasMax > 0 ? String.valueOf(monedasMax) : "infinito";
            return "monedas " + a + "-" + b;
        }

        String clave() {
            if (giftId > 0) return "id:" + giftId;
            if (nombre != null && !nombre.isEmpty()) return "nom:" + Regalos.norm(nombre);
            if (monedas > 0) return "mon:" + monedas;
            return "tramo:" + monedasMin + "-" + monedasMax;
        }
    }

    private static final List<Regla> REGLAS = new ArrayList<>();

    public static List<Regla> reglas() { return REGLAS; }

    public static String mobPara(long giftId, String nombre, int monedas) {
        Regla r = reglaPara(giftId, nombre, monedas);
        return r == null ? null : r.mob;
    }

    public static Regla reglaPara(long giftId, String nombre, int monedas) {
        Regla mejor = null;
        for (Regla r : REGLAS) {
            if (!r.encaja(giftId, nombre, monedas)) continue;
            if (mejor == null
                    || r.especificidad() > mejor.especificidad()
                    || (r.especificidad() == mejor.especificidad() && r.anchoTramo() < mejor.anchoTramo()))
                mejor = r;
        }
        return mejor;
    }

    public static void poner(Regla nueva) {
        REGLAS.removeIf(r -> r.clave().equals(nueva.clave()));
        REGLAS.add(nueva);
        REGLAS.sort(Comparator.comparingInt((Regla r) -> -r.especificidad()));
    }

    public static boolean quitar(int n) {
        if (n < 1 || n > REGLAS.size()) return false;
        REGLAS.remove(n - 1);
        return true;
    }

    // ---------------- validacion del mob ----------------

    public static boolean existeMob(String id) { return tipo(id) != null; }

    private static EntityType tipo(String id) {
        if (id == null || id.isEmpty()) return null;
        String completo = id.contains(":") ? id : ("minecraft:" + id);
        NamespacedKey key = NamespacedKey.fromString(completo);
        if (key == null) return null;
        return Registry.ENTITY_TYPE.get(key);
    }

    /** Sugerencias para el autocompletado del comando: todos los mobs registrados. */
    public static List<String> idsSugeridos() {
        List<String> out = new ArrayList<>();
        for (EntityType t : Registry.ENTITY_TYPE) out.add(t.getKey().toString());
        return out;
    }

    // ---------------- soltar una mascota ----------------

    public static final class Resultado {
        public final boolean ok;
        public final String detalle;
        public final House casa;      // null si salio en la plaza
        Resultado(boolean ok, String d, House c) { this.ok = ok; detalle = d; casa = c; }
    }

    /**
     * Da una mascota a un donador. Si no tiene casa todavia, sale en la plaza.
     *
     * @param mobId      id de entidad ("minecraft:cat")
     * @param donadorRaw nombre tal como llega de TikTok; se limpia aqui
     */
    public static Resultado soltar(Village v, String mobId, String donadorRaw) {
        if (v == null) return new Resultado(false, "no hay pueblo", null);
        EntityType type = tipo(mobId);
        if (type == null) return new Resultado(false, "no existe la entidad '" + mobId + "'", null);

        World w = VillageEngine.worldOf(v);
        String limpio = NameUtil.clean(donadorRaw);
        House destino = casaPara(v, limpio);

        int cx, cz, y0;
        if (destino != null) { cx = destino.x; cz = destino.z; y0 = destino.y; }
        else { cx = v.plazaX; cz = v.plazaZ; y0 = v.plazaY; }

        int[] at = sitioLibre(w, cx, cz, y0);
        if (at == null) return new Resultado(false, "no encontre hueco donde soltarlo", destino);

        Entity e = crear(w, type, at, limpio);
        if (e == null) return new Resultado(false, "no pude crear la entidad", destino);
        if (destino != null) destino.mobs.add(new House.Mascota(e.getUniqueId().toString(),
                type.getKey().toString(), limpio));

        String donde = destino != null ? ("casa #" + destino.num) : "la plaza";
        return new Resultado(true, "mascota de '" + limpio + "' en " + donde, destino);
    }

    /** Casa que le toca a este donador: la primera suya con hueco; si todas estan
     *  llenas, la ultima; si no tiene ninguna, null (saldra en la plaza). */
    private static House casaPara(Village v, String donadorLimpio) {
        List<House> suyas = new ArrayList<>();
        for (House h : v.houses)
            if (h.name != null && h.name.equalsIgnoreCase(donadorLimpio)) suyas.add(h);
        if (suyas.isEmpty()) return null;
        suyas.sort(Comparator.comparingInt(h -> h.num));
        for (House h : suyas) if (h.mobs.size() < Cfg.MOBS_POR_CASA) return h;
        return suyas.get(suyas.size() - 1);
    }

    /** Hueco donde quepa el mob: dos bloques de aire con suelo solido debajo. Se busca
     *  en anillos desde el centro hacia fuera, asi que la mascota sale lo mas cerca
     *  posible del sitio pedido (dentro de la casa si cabe, y si no en el jardin). */
    private static int[] sitioLibre(World w, int cx, int cz, int y0) {
        for (int r = 0; r <= Cfg.MOB_RADIO_BUSQUEDA; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue;  // solo el anillo
                    int x = cx + dx, z = cz + dz;
                    for (int dy = 0; dy <= 4; dy++)
                        if (cabe(w, x, y0 + dy, z)) return new int[]{x, y0 + dy, z};
                }
            }
        }
        return null;
    }

    /** Hay sitio si el mob tiene dos bloques de aire y algo donde pisar. El suelo NO se
     *  exige que sea bloque completo a proposito: los caminos llevan losas y escaleras,
     *  y exigir cubo entero dejaba sin sitio a media casa. */
    private static boolean cabe(World w, int x, int y, int z) {
        Block suelo = w.getBlockAt(x, y - 1, z);
        if (suelo.getType().isAir() || suelo.isLiquid()) return false;
        if (!w.getBlockAt(x, y, z).getType().isAir()) return false;
        return w.getBlockAt(x, y + 1, z).getType().isAir();
    }

    private static Entity crear(World w, EntityType type, int[] at, String nombre) {
        Location loc = new Location(w, at[0] + 0.5, at[1], at[2] + 0.5);
        Entity e = w.spawn(loc, type.getEntityClass());
        if (e instanceof Mob m && m instanceof LivingEntity le) {
            le.setPersistent(true);                      // que no lo despachen los despawns
        }
        e.setInvulnerable(Cfg.MOB_INVULNERABLE);
        e.customName(Component.text(nombre));
        e.setCustomNameVisible(Cfg.MOB_NOMBRE_VISIBLE);
        return e;
    }

    // ---------------- borrado ----------------

    /** Mata las mascotas de una casa. Se llama al quitar la casa. */
    public static int borrarDe(World w, House h) {
        int n = 0;
        for (House.Mascota m : h.mobs) {
            Entity e = buscar(w, m.uuid);
            if (e != null) { e.remove(); n++; }
        }
        h.mobs.clear();
        return n;
    }

    /** Lleva a su casa las mascotas de un donador que salieron en la plaza. */
    public static int recoger(Village v, String donadorLimpio) {
        World w = VillageEngine.worldOf(v);
        House destino = casaPara(v, donadorLimpio);
        if (destino == null) return 0;

        int movidos = 0;
        Location plaza = new Location(w, v.plazaX, v.plazaY, v.plazaZ);
        double radio = v.halfPlaza() + Cfg.MOB_RADIO_BUSQUEDA + 2;
        for (Entity e : w.getNearbyEntities(plaza, radio, radio + 4, radio)) {
            if (destino.mobs.size() >= Cfg.MOBS_POR_CASA) break;
            Component cn = e.customName();
            if (cn == null) continue;
            String texto = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(cn);
            if (!texto.equalsIgnoreCase(donadorLimpio)) continue;
            if (!(e instanceof Mob)) continue;
            if (yaRegistrada(v, e.getUniqueId().toString())) continue;
            int[] at = sitioLibre(w, destino.x, destino.z, destino.y);
            if (at == null) break;
            e.teleport(new Location(w, at[0] + 0.5, at[1], at[2] + 0.5));
            destino.mobs.add(new House.Mascota(e.getUniqueId().toString(),
                    e.getType().getKey().toString(), donadorLimpio));
            movidos++;
        }
        return movidos;
    }

    private static boolean yaRegistrada(Village v, String uuid) {
        for (House h : v.houses)
            for (House.Mascota m : h.mobs)
                if (m.uuid.equals(uuid)) return true;
        return false;
    }

    private static Entity buscar(World w, String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        try {
            return w.getEntity(UUID.fromString(uuid));
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    // ---------------- fichero ----------------

    private static File ruta;

    public static void usarCarpeta(File carpetaConfig) { ruta = new File(carpetaConfig, ARCHIVO); }

    /** Carga la tabla. Devuelve un aviso, o null si fue bien. */
    public static String cargar() {
        if (ruta == null) return "sin ruta de config: tabla de mascotas vacia";
        try {
            if (!ruta.exists()) { REGLAS.clear(); guardar(); return null; }
            String txt = Files.readString(ruta.toPath(), StandardCharsets.UTF_8);
            JsonObject raiz = JsonParser.parseString(txt).getAsJsonObject();
            List<Regla> nuevas = new ArrayList<>();
            int malas = 0;
            if (raiz.has("reglas")) {
                for (JsonElement e : raiz.getAsJsonArray("reglas")) {
                    JsonObject o = e.getAsJsonObject();
                    Regla r = new Regla();
                    r.giftId    = o.has("giftId") ? o.get("giftId").getAsLong() : 0;
                    r.nombre    = o.has("nombre") ? o.get("nombre").getAsString() : null;
                    r.monedas   = o.has("monedas") ? o.get("monedas").getAsInt() : 0;
                    r.monedasMin = o.has("monedasMin") ? o.get("monedasMin").getAsInt() : 0;
                    r.monedasMax = o.has("monedasMax") ? o.get("monedasMax").getAsInt() : 0;
                    r.mob       = o.has("mob") ? o.get("mob").getAsString() : null;
                    if (r.mob == null || r.mob.isEmpty()) { malas++; continue; }
                    nuevas.add(r);
                }
            }
            REGLAS.clear();
            REGLAS.addAll(nuevas);
            REGLAS.sort(Comparator.comparingInt((Regla r) -> -r.especificidad()));
            return malas > 0 ? (malas + " regla(s) de mascota ignorada(s) por estar mal escritas") : null;
        } catch (Exception e) {
            return "no pude leer " + ARCHIVO + " (" + e.getMessage() + "): dejo la tabla anterior";
        }
    }

    public static void guardar() throws IOException {
        if (ruta == null) return;
        JsonArray arr = new JsonArray();
        for (Regla r : REGLAS) {
            JsonObject o = new JsonObject();
            if (r.giftId > 0) o.addProperty("giftId", r.giftId);
            if (r.nombre != null && !r.nombre.isEmpty()) o.addProperty("nombre", r.nombre);
            if (r.monedas > 0) o.addProperty("monedas", r.monedas);
            if (r.monedasMin > 0) o.addProperty("monedasMin", r.monedasMin);
            if (r.monedasMax > 0) o.addProperty("monedasMax", r.monedasMax);
            o.addProperty("mob", r.mob == null ? "" : r.mob);
            arr.add(o);
        }
        JsonObject raiz = new JsonObject();
        raiz.addProperty("_ayuda", "Que MASCOTA da cada regalo. Es la tabla hermana de "
            + ARCHIVO.replace("mascotas", "regalos") + ": un regalo puede dar una casa, una "
            + "mascota, o las dos si aparece en las dos tablas. 'mob' es un id de entidad "
            + "(minecraft:cat, minecraft:wolf, minecraft:parrot...). Gana la regla mas "
            + "concreta: giftId > nombre > monedas > tramo.");
        raiz.add("reglas", arr);
        ruta.getParentFile().mkdirs();
        Files.writeString(ruta.toPath(),
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(raiz),
            StandardCharsets.UTF_8);
    }
}
