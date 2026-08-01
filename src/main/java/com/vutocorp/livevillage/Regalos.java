package com.vutocorp.livevillage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Que casa da cada regalo de TikTok. Port literal de Regalos.java del mod: es Java
 * puro, cero imports de Minecraft en el original. Unico cambio: el mod traduce el
 * campo "modelo" con Skins.migrarModelo() (renombrado japoncasaN->japonhouseN de su
 * version 0.6.0); el plugin es producto nuevo, no tiene ese pasado que arreglar.
 *
 * ---- Por que el giftId es la clave buena ----
 * El evento de TikTok trae `giftId` (numero), `giftName` (texto) y `diamondCount`
 * (monedas). El NOMBRE depende del idioma con el que se conecta la libreria y ademas
 * solo llega si se pide la info extendida del regalo. El giftId no cambia con nada.
 *
 * Se admiten cuatro formas de regla, y gana la MAS ESPECIFICA: giftId > nombre >
 * monedas exactas > tramo. Para saber el giftId de un regalo: /lv gift log on.
 */
public final class Regalos {

    private Regalos() {}

    public static final String ARCHIVO = "livevillage-regalos.json";

    public static final class Regla {
        public long giftId;
        public String nombre;
        public int monedas;
        public int monedasMin;
        public int monedasMax;
        public String modelo;

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
            if (nombre != null && !nombre.isEmpty()) return norm(nombre).equals(norm(nom));
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
    }

    /** Nombres comparables: sin mayusculas, sin acentos, sin espacios de sobra. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
                             .replaceAll("\\p{M}+", "")
                             .toLowerCase(Locale.ROOT)
                             .replaceAll("\\s+", " ");
        String alias = ALIAS.get(n);
        return alias != null ? alias : n;
    }

    /** Alias español -> ingles (la conexion se pide en ingles para que los nombres
     *  lleguen siempre iguales). Comodidad, no la solucion: si un regalo no esta
     *  aqui, usa su giftId, que es a prueba de todo. */
    private static final Map<String, String> ALIAS = new HashMap<>();
    static {
        alias("rosa", "rose");
        alias("galaxia", "galaxy");
        alias("pistola de dinero", "money gun");
        alias("corazon", "heart");
        alias("leon", "lion");
        alias("universo", "universe");
        alias("cohete", "rocket");
        alias("oso", "bear");
        alias("helado", "ice cream cone");
        alias("me gusta", "like");
        alias("dedos del corazon", "finger heart");
        alias("gorra", "hat");
    }
    private static void alias(String es, String en) { ALIAS.put(es, en); }

    private static final List<Regla> REGLAS = new ArrayList<>();
    private static File ruta;

    public static List<Regla> reglas() { return REGLAS; }

    /** Resuelve que modelo toca. null = ese regalo no genera casa. */
    public static String modeloPara(long giftId, String nombre, int monedas) {
        Regla mejor = null;
        for (Regla r : REGLAS) {
            if (!r.encaja(giftId, nombre, monedas)) continue;
            if (mejor == null) { mejor = r; continue; }
            int a = r.especificidad(), b = mejor.especificidad();
            if (a > b || (a == b && r.anchoTramo() < mejor.anchoTramo())) mejor = r;
        }
        return mejor == null ? null : mejor.modelo;
    }

    /** Regla que ganaria, para poder explicarlo en /lv gift test. */
    public static Regla reglaPara(long giftId, String nombre, int monedas) {
        Regla mejor = null;
        for (Regla r : REGLAS) {
            if (!r.encaja(giftId, nombre, monedas)) continue;
            if (mejor == null) { mejor = r; continue; }
            int a = r.especificidad(), b = mejor.especificidad();
            if (a > b || (a == b && r.anchoTramo() < mejor.anchoTramo())) mejor = r;
        }
        return mejor;
    }

    public static void poner(Regla nueva) {
        REGLAS.removeIf(r -> mismaClave(r, nueva));
        REGLAS.add(nueva);
        ordenar();
    }

    public static boolean quitar(String clave) {
        return REGLAS.removeIf(r -> r.describe().equalsIgnoreCase(clave)
                || (r.nombre != null && norm(r.nombre).equals(norm(clave)))
                || String.valueOf(r.giftId).equals(clave));
    }

    private static boolean mismaClave(Regla a, Regla b) {
        if (a.giftId > 0 || b.giftId > 0) return a.giftId == b.giftId;
        if (a.nombre != null && b.nombre != null) return norm(a.nombre).equals(norm(b.nombre));
        if (a.monedas > 0 || b.monedas > 0) return a.monedas == b.monedas;
        return a.monedasMin == b.monedasMin && a.monedasMax == b.monedasMax;
    }

    private static void ordenar() {
        REGLAS.sort(Comparator.<Regla>comparingInt(r -> -r.especificidad())
                              .thenComparingInt(Regla::anchoTramo));
    }

    // ---------------- fichero ----------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static void usarCarpeta(File carpetaConfig) {
        ruta = new File(carpetaConfig, ARCHIVO);
    }

    /** Carga el fichero. Si no existe, escribe uno de ejemplo. Devuelve un aviso o null. */
    public static String cargar() {
        REGLAS.clear();
        if (ruta == null) { porDefecto(); return "sin ruta de config: usando la tabla por defecto"; }
        try {
            if (!ruta.exists()) {
                porDefecto();
                guardar();
                return null;
            }
            String txt = Files.readString(ruta.toPath(), StandardCharsets.UTF_8);
            JsonElement raiz = JsonParser.parseString(txt);
            JsonArray arr = raiz.isJsonArray() ? raiz.getAsJsonArray()
                                               : raiz.getAsJsonObject().getAsJsonArray("regalos");
            int malas = 0;
            for (JsonElement e : arr) {
                Regla r = leer(e.getAsJsonObject());
                if (r == null) { malas++; continue; }
                REGLAS.add(r);
            }
            ordenar();
            if (REGLAS.isEmpty()) { porDefecto(); return "el fichero no tenia ninguna regla valida"; }
            return malas > 0 ? (malas + " regla(s) ignorada(s) por estar mal escritas") : null;
        } catch (Exception e) {
            porDefecto();
            return "no pude leer " + ARCHIVO + " (" + e.getMessage() + "): uso la tabla por defecto";
        }
    }

    private static Regla leer(JsonObject o) {
        Regla r = new Regla();
        if (o.has("giftId")) r.giftId = o.get("giftId").getAsLong();
        if (o.has("nombre")) r.nombre = o.get("nombre").getAsString();
        if (o.has("monedas")) r.monedas = o.get("monedas").getAsInt();
        if (o.has("monedasMin")) r.monedasMin = o.get("monedasMin").getAsInt();
        if (o.has("monedasMax")) r.monedasMax = o.get("monedasMax").getAsInt();
        if (o.has("modelo")) r.modelo = o.get("modelo").getAsString();
        if (r.modelo == null || r.modelo.isEmpty()) return null;
        if (r.giftId <= 0 && (r.nombre == null || r.nombre.isEmpty())
                && r.monedas <= 0 && r.monedasMin <= 0 && r.monedasMax <= 0) return null;
        return r;
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
            o.addProperty("modelo", r.modelo);
            arr.add(o);
        }
        JsonObject raiz = new JsonObject();
        raiz.addProperty("_ayuda", "Reglas de regalo -> casa. Gana la mas especifica: "
            + "giftId > nombre > monedas exactas > tramo. Para saber el giftId de un regalo, "
            + "usa /lv gift log on y envialo en tu directo.");
        raiz.add("regalos", arr);
        ruta.getParentFile().mkdirs();
        Files.writeString(ruta.toPath(), GSON.toJson(raiz), StandardCharsets.UTF_8);
    }

    // ---------------- registro de regalos vistos ----------------

    public static boolean registrando = false;
    private static final java.util.LinkedHashMap<Long, String> VISTOS = new java.util.LinkedHashMap<>();

    public static void anotarVisto(long giftId, String nombre, int monedas) {
        VISTOS.put(giftId, nombre + "  (" + monedas + " monedas)");
        while (VISTOS.size() > 40) VISTOS.remove(VISTOS.keySet().iterator().next());
    }

    public static java.util.Map<Long, String> vistos() { return VISTOS; }

    private static void porDefecto() {
        REGLAS.clear();
        Regla a = new Regla(); a.monedas = 500;  a.modelo = "japonhouse1"; REGLAS.add(a);
        Regla b = new Regla(); b.monedas = 1000; b.modelo = "japonhouse2"; REGLAS.add(b);
        Regla c = new Regla(); c.monedasMin = 1001; c.modelo = "japonhouse2"; REGLAS.add(c);
        ordenar();
    }
}
