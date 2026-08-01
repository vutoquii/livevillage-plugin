package com.vutocorp.livevillage;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogo de skins y modelos. Port de Skins.java del mod.
 *
 * Diferencia con el mod: alli las estructuras se nombran con namespace
 * ("livevillage:japonhouse1") porque van al registro de Minecraft. Aqui las resuelve
 * Structures contra la carpeta del plugin y el jar, asi que el nombre es liso
 * ("japonhouse1"). Si un catalogo trae namespace se le quita al leerlo, para que un
 * livevillage-modelos.json copiado del mod siga funcionando.
 *
 * NO se portan migrarModelo()/migrarEstructura() (el renombrado japoncasaN->japonhouseN
 * de la version 0.6.0 del mod): el plugin es producto nuevo, no hay configs viejos suyos
 * que arreglar, y arrastrar esa traduccion solo serviria para esconder erratas.
 */
public final class Skins {

    private Skins() {}

    public static final class Model {
        public final String id;
        public final String skin;
        public final String structure;   // nombre liso del .nbt, sin namespace
        public final int coins;
        public final String gift;
        Model(String id, String skin, String structure, int coins, String gift) {
            this.id = id; this.skin = skin;
            this.structure = sinNamespace(structure);
            this.coins = coins; this.gift = gift;
        }
        /** Cuanto se levanta esta casa sobre el terreno. Lo define su skin. */
        public int lift() {
            Skin s = skin(skin);
            return s == null ? 0 : s.lift;
        }
    }

    public static final class Skin {
        public final String name;
        public final String plaza, lamp;
        /** Arbol decorativo del camino. null = esta skin no planta arboles. */
        public final String tree;
        /** Bloques que se sube el suelo de las CASAS de esta skin sobre el terreno.
         *  Es por skin porque depende de como este construido el modelo: la japonesa
         *  lleva la capa 0 como cimiento y queda mejor asomando. */
        public final int lift;
        Skin(String name, String plaza, String lamp, int lift, String tree) {
            this.name = name;
            this.plaza = sinNamespace(plaza);
            this.lamp = sinNamespace(lamp);
            this.lift = lift;
            this.tree = (tree == null || tree.isEmpty()) ? null : sinNamespace(tree);
        }
    }

    /** "livevillage:japonhouse1" -> "japonhouse1". Deja tal cual lo que ya viene liso. */
    private static String sinNamespace(String s) {
        if (s == null) return null;
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }

    private static final Map<String, Model> MODELS = new LinkedHashMap<>();
    private static final Map<String, Skin> SKINS = new LinkedHashMap<>();
    /** Copia intacta del catalogo de fabrica, para heredar campos que un json antiguo no tenga. */
    private static final Map<String, Skin> SKINS_FABRICA = new LinkedHashMap<>();

    static {
        skin("japon", "japonplaza1", "japonposte1", 1, "japontree");
        model("japonhouse1", "japon", "japonhouse1", 500,  "Money gun / Pistola de dinero");
        model("japonhouse2", "japon", "japonhouse2", 1000, "Galaxia");
        model("japonhouse3", "japon", "japonhouse3", 2000, "Universo / Leon");
        SKINS_FABRICA.putAll(SKINS);
    }

    private static void model(String id, String skin, String structure, int coins, String gift) {
        MODELS.put(id, new Model(id, skin, structure, coins, gift));
    }
    private static void skin(String name, String plaza, String lamp, int lift, String tree) {
        SKINS.put(name, new Skin(name, plaza, lamp, lift, tree));
    }

    // ================= catalogo en fichero =================

    public static final String ARCHIVO = "livevillage-modelos.json";
    private static File ruta;

    public static void usarCarpeta(File carpetaConfig) {
        ruta = new File(carpetaConfig, ARCHIVO);
    }

    /** Carga el catalogo. Devuelve un aviso, o null si todo fue bien. */
    public static String cargar() {
        if (ruta == null) return "sin ruta de config: uso el catalogo de ejemplo";
        try {
            if (!ruta.exists()) { guardar(); return null; }
            String txt = Files.readString(ruta.toPath(), StandardCharsets.UTF_8);
            JsonObject raiz = JsonParser.parseString(txt).getAsJsonObject();

            Map<String, Skin> nuevasSkins = new LinkedHashMap<>();
            Map<String, Model> nuevosModelos = new LinkedHashMap<>();
            int malas = 0;

            for (JsonElement e : raiz.getAsJsonArray("skins")) {
                JsonObject o = e.getAsJsonObject();
                String nombre = texto(o, "nombre", null);
                if (nombre == null) { malas++; continue; }
                // OJO con "arbol": un json escrito ANTES de que existieran los arboles no
                // trae la clave, y con un defecto de "" la skin se quedaria sin arbol para
                // siempre aunque el catalogo de fabrica si lo tenga. Hay que distinguir
                // "la clave no esta" (heredar de fabrica) de "la clave esta vacia" (el
                // usuario los quito a proposito).
                Skin fab = SKINS_FABRICA.get(nombre);
                String arbol = o.has("arbol")
                        ? texto(o, "arbol", "")
                        : (fab != null && fab.tree != null ? fab.tree : "");
                nuevasSkins.put(nombre, new Skin(nombre,
                    texto(o, "plaza", ""), texto(o, "poste", ""),
                    o.has("lift") ? o.get("lift").getAsInt() : 0,
                    arbol));
            }
            for (JsonElement e : raiz.getAsJsonArray("modelos")) {
                JsonObject o = e.getAsJsonObject();
                String id = texto(o, "id", null);
                String skin = texto(o, "skin", null);
                String est = texto(o, "estructura", null);
                if (id == null || skin == null || est == null) { malas++; continue; }
                if (!nuevasSkins.containsKey(skin)) { malas++; continue; }   // skin inexistente
                nuevosModelos.put(id, new Model(id, skin, est,
                    o.has("monedas") ? o.get("monedas").getAsInt() : 0,
                    texto(o, "regalo", "")));
            }
            if (nuevasSkins.isEmpty() || nuevosModelos.isEmpty())
                return "el fichero no tenia skins o modelos validos: dejo el catalogo anterior";

            SKINS.clear(); SKINS.putAll(nuevasSkins);
            MODELS.clear(); MODELS.putAll(nuevosModelos);
            return malas > 0 ? (malas + " entrada(s) ignorada(s) por estar mal escritas") : null;
        } catch (Exception e) {
            // Un JSON roto no puede dejar el plugin sin casas ni tumbar el servidor.
            return "no pude leer " + ARCHIVO + " (" + e.getMessage() + "): dejo el catalogo anterior";
        }
    }

    private static String texto(JsonObject o, String k, String pd) {
        return o.has(k) ? o.get(k).getAsString() : pd;
    }

    public static void guardar() throws IOException {
        if (ruta == null) return;
        JsonArray aSkins = new JsonArray();
        for (Skin s : SKINS.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("nombre", s.name);
            o.addProperty("plaza", s.plaza);
            o.addProperty("poste", s.lamp);
            o.addProperty("lift", s.lift);
            o.addProperty("arbol", s.tree == null ? "" : s.tree);
            aSkins.add(o);
        }
        JsonArray aModelos = new JsonArray();
        for (Model m : MODELS.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.id);
            o.addProperty("skin", m.skin);
            o.addProperty("estructura", m.structure);
            o.addProperty("monedas", m.coins);
            o.addProperty("regalo", m.gift == null ? "" : m.gift);
            aModelos.add(o);
        }
        JsonObject raiz = new JsonObject();
        raiz.addProperty("_ayuda", "Catalogo de casas. 'estructura' es el nombre del .nbt sin "
            + "extension. Los .nbt propios van en plugins/LiveVillage/structures/ y pisan a "
            + "los del jar. 'lift' es cuantos bloques se levantan las casas de esa skin. "
            + "'arbol' es el .nbt que se planta junto a los caminos ('' = ninguno).");
        raiz.add("skins", aSkins);
        raiz.add("modelos", aModelos);
        ruta.getParentFile().mkdirs();
        Files.writeString(ruta.toPath(),
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(raiz),
            StandardCharsets.UTF_8);
    }

    // ---- consulta ----
    public static Model model(String id) { return id == null ? null : MODELS.get(id); }
    public static Skin skin(String name) { return name == null ? null : SKINS.get(name); }
    public static List<Model> models() { return new ArrayList<>(MODELS.values()); }
    public static List<String> modelIds() { return new ArrayList<>(MODELS.keySet()); }
    public static List<String> skinNames() { return new ArrayList<>(SKINS.keySet()); }

    /** Modelos de una skin, del mas barato al mas caro. */
    public static List<Model> modelsOf(String skin) {
        List<Model> out = new ArrayList<>();
        for (Model m : MODELS.values()) if (m.skin.equals(skin)) out.add(m);
        out.sort((a, b) -> Integer.compare(a.coins, b.coins));
        return out;
    }

    /** Modelo por defecto de una skin: el mas barato. */
    public static Model cheapest(String skin) {
        List<Model> l = modelsOf(skin);
        return l.isEmpty() ? null : l.get(0);
    }
}
