package com.vutocorp.livevillage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Una casa de donador. Calco de House.java del mod para la parte de datos.
 *
 * El registro de cambios (para restaurar el terreno al quitar la casa) guarda
 * el estado anterior como el texto de BlockData ("minecraft:oak_stairs[facing=north,...]")
 * en vez del NBT que usa el mod. Es reversible con Bukkit.createBlockData() y ademas
 * queda legible en el YAML, que ayuda si hay que depurar un 'remove' a mano.
 */
public class House {

    public String donorRaw;
    public String name;
    public int num;
    public int x, y, z;
    public String facing = "north";

    public String modelId;
    public int sizeX = 0, sizeZ = 0;
    public Pos3 markVillager, markJob, markSign;
    public String villagerId;
    public String profession;

    public static final class Mascota {
        public final String uuid, tipo, nombre;
        public Mascota(String uuid, String tipo, String nombre) {
            this.uuid = uuid; this.tipo = tipo; this.nombre = nombre;
        }
    }
    public final List<Mascota> mobs = new ArrayList<>();

    /** Un bloque que tocamos, con lo que habia antes, para poder deshacerlo. */
    public static final class Cambio {
        public final int x, y, z;
        public final String antes;   // BlockData en texto
        public Cambio(int x, int y, int z, String antes) {
            this.x = x; this.y = y; this.z = z; this.antes = antes;
        }
    }
    public final List<Cambio> cambios = new ArrayList<>();

    public void recordChange(int x, int y, int z, String antes) {
        cambios.add(new Cambio(x, y, z, antes));
    }

    public House() {}

    public House(String donorRaw, String name, int x, int y, int z, String facing) {
        this.donorRaw = donorRaw;
        this.name = name;
        this.x = x; this.y = y; this.z = z;
        this.facing = facing;
    }

    public int halfX() { return sizeX > 0 ? sizeX / 2 : Cfg.PLOT_HALF; }
    public int halfZ() { return sizeZ > 0 ? sizeZ / 2 : Cfg.PLOT_HALF; }
    public int halfMax() { return Math.max(halfX(), halfZ()); }

    /**
     * Y donde parar los pies para quedar de pie en el suelo de la casa, NO en el
     * terreno. Trampa documentada en CLAUDE.md: 'y' es la altura del TERRENO cuando se
     * sorteo la parcela, no el suelo acabado; con Skin.lift > 0 no coinciden. Un
     * teletransporte a "y + 1" a secas deja al jugador metido en el suelo cuando la
     * skin levanta la casa (la japonesa, lift=1).
     */
    public int floorY() {
        Skins.Model m = Skins.model(modelId);
        int lift = m == null ? 0 : m.lift();
        return y + lift + 1;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("donorRaw", donorRaw == null ? "" : donorRaw);
        m.put("name", name == null ? "" : name);
        m.put("num", num);
        m.put("x", x); m.put("y", y); m.put("z", z);
        m.put("facing", facing == null ? "north" : facing);
        if (modelId != null) m.put("modelId", modelId);
        m.put("sizeX", sizeX);
        m.put("sizeZ", sizeZ);
        if (villagerId != null) m.put("villagerId", villagerId);
        if (profession != null) m.put("profession", profession);
        if (markVillager != null) m.put("markVillager", markVillager.toMap());
        if (markJob != null) m.put("markJob", markJob.toMap());
        if (markSign != null) m.put("markSign", markSign.toMap());
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Mascota mo : mobs) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("uuid", mo.uuid);
            mm.put("tipo", mo.tipo == null ? "" : mo.tipo);
            mm.put("nombre", mo.nombre == null ? "" : mo.nombre);
            lista.add(mm);
        }
        m.put("mobs", lista);
        // Los cambios se guardan como "x,y,z=blockdata": una lista de mapas por cada
        // bloque haria el YAML enorme (una casa toca cientos de bloques).
        List<String> cs = new ArrayList<>();
        for (Cambio c : cambios) cs.add(c.x + "," + c.y + "," + c.z + "=" + c.antes);
        m.put("cambios", cs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static House fromMap(Map<String, Object> m) {
        House h = new House(
            str(m.get("donorRaw")), str(m.get("name")),
            num(m.get("x")), num(m.get("y")), num(m.get("z")),
            m.get("facing") == null ? "north" : str(m.get("facing"))
        );
        h.num = num(m.get("num"));
        if (m.get("modelId") != null) h.modelId = str(m.get("modelId"));
        h.sizeX = num(m.get("sizeX"));
        h.sizeZ = num(m.get("sizeZ"));
        if (m.get("villagerId") != null) h.villagerId = str(m.get("villagerId"));
        if (m.get("profession") != null) h.profession = str(m.get("profession"));
        h.markVillager = Pos3.fromMap(m.get("markVillager"));
        h.markJob = Pos3.fromMap(m.get("markJob"));
        h.markSign = Pos3.fromMap(m.get("markSign"));
        Object lista = m.get("mobs");
        if (lista instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> mm = (Map<String, Object>) o;
                h.mobs.add(new Mascota(str(mm.get("uuid")), str(mm.get("tipo")), str(mm.get("nombre"))));
            }
        }
        Object cs = m.get("cambios");
        if (cs instanceof List<?> l) {
            for (Object o : l) {
                String s = o == null ? "" : o.toString();
                int eq = s.indexOf('=');
                if (eq < 0) continue;
                String[] xyz = s.substring(0, eq).split(",");
                if (xyz.length != 3) continue;
                try {
                    h.cambios.add(new Cambio(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]),
                                             Integer.parseInt(xyz[2]), s.substring(eq + 1)));
                } catch (NumberFormatException ignored) {
                    // una linea corrupta no puede impedir que cargue el resto de la casa
                }
            }
        }
        return h;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
}
