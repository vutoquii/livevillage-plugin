package com.vutocorp.livevillage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Un pueblo. Calco de Village.java del mod: mismo modelo de datos, "dimension"
 * (String tipo "minecraft:overworld") pasa a ser el nombre del World de Bukkit,
 * que es igual de estable como clave de guardado.
 */
public class Village {

    public String name;
    public String owner;       // UUID en texto. Vacio = sin dueño (solo admins)
    public String ownerName = "";
    public boolean open = true;
    public String world;       // nombre del World de Bukkit
    public String skin;        // null = Cfg.DEFAULT_SKIN
    public boolean forced = false;
    public int nextNum = 0;
    public boolean aiPause = true;
    public Pos3 waystone;
    public int plazaHalf = 0;
    public int plazaX, plazaY, plazaZ;
    public final List<House> houses = new ArrayList<>();
    public final Set<Long> pathCells = new HashSet<>();
    public final Set<Long> lampCells = new HashSet<>();
    public final Set<Long> treeCells = new HashSet<>();

    public Village() {}

    public Village(String name, String owner, String world, int plazaX, int plazaY, int plazaZ) {
        this.name = name;
        this.owner = owner;
        this.world = world;
        this.plazaX = plazaX; this.plazaY = plazaY; this.plazaZ = plazaZ;
    }

    public int halfPlaza() { return plazaHalf > 0 ? plazaHalf : Cfg.PLAZA_HALF; }

    public House byNum(int num) {
        for (House h : houses) if (h.num == num) return h;
        return null;
    }

    public int indexOfNum(int num) {
        for (int i = 0; i < houses.size(); i++) if (houses.get(i).num == num) return i;
        return -1;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("owner", owner == null ? "" : owner);
        m.put("ownerName", ownerName == null ? "" : ownerName);
        m.put("open", open);
        m.put("world", world == null ? "world" : world);
        m.put("skin", skin == null ? Cfg.DEFAULT_SKIN : skin);
        m.put("forced", forced);
        m.put("nextNum", nextNum);
        m.put("plazaHalf", plazaHalf);
        m.put("aiPause", aiPause);
        if (waystone != null) m.put("waystone", waystone.toMap());
        m.put("plazaX", plazaX);
        m.put("plazaY", plazaY);
        m.put("plazaZ", plazaZ);
        List<Map<String, Object>> hs = new ArrayList<>();
        for (House h : houses) hs.add(h.toMap());
        m.put("houses", hs);
        m.put("pathCells", new ArrayList<>(pathCells));
        m.put("lampCells", new ArrayList<>(lampCells));
        m.put("treeCells", new ArrayList<>(treeCells));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Village fromMap(String name, Map<String, Object> m) {
        Village v = new Village();
        v.name = name;
        v.owner = str(m.get("owner"));
        v.ownerName = str(m.get("ownerName"));
        v.open = bool(m.get("open"), true);
        v.world = m.get("world") != null ? str(m.get("world")) : "world";
        v.skin = m.get("skin") != null ? str(m.get("skin")) : Cfg.DEFAULT_SKIN;
        v.forced = bool(m.get("forced"), false);
        v.nextNum = num(m.get("nextNum"));
        v.plazaHalf = num(m.get("plazaHalf"));
        v.aiPause = bool(m.get("aiPause"), true);
        v.waystone = Pos3.fromMap(m.get("waystone"));
        v.plazaX = num(m.get("plazaX"));
        v.plazaY = num(m.get("plazaY"));
        v.plazaZ = num(m.get("plazaZ"));
        Object houses = m.get("houses");
        if (houses instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map) v.houses.add(House.fromMap((Map<String, Object>) o));
        }
        addLongs(m.get("pathCells"), v.pathCells);
        addLongs(m.get("lampCells"), v.lampCells);
        addLongs(m.get("treeCells"), v.treeCells);
        for (House h : v.houses) if (h.num <= 0) h.num = ++v.nextNum;
        for (House h : v.houses) if (h.num > v.nextNum) v.nextNum = h.num;
        return v;
    }

    @SuppressWarnings("unchecked")
    private static void addLongs(Object o, Set<Long> dest) {
        if (o instanceof List<?> l) for (Object e : l) if (e instanceof Number n) dest.add(n.longValue());
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static boolean bool(Object o, boolean def) { return o instanceof Boolean b ? b : def; }
}
