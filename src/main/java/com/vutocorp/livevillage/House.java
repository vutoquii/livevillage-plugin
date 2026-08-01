package com.vutocorp.livevillage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Una casa de donador. Calco de House.java del mod para la parte de datos.
 * Lo que en el mod es "changePos/changeOld" (restaurar terreno al quitar la
 * casa) llega en la Fase 2 junto con VillageEngine: aqui no hace falta
 * todavia porque este esqueleto no coloca bloques.
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
        return h;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
}
