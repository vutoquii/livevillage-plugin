package com.vutocorp.livevillage;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tres enteros con nombre, para posiciones guardadas en YAML sin arrastrar BlockPos. */
public final class Pos3 {
    public final int x, y, z;

    public Pos3(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x); m.put("y", y); m.put("z", z);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Pos3 fromMap(Object o) {
        if (!(o instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) o;
        return new Pos3(num(m.get("x")), num(m.get("y")), num(m.get("z")));
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
}
