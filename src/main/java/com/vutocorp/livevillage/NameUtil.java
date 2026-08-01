package com.vutocorp.livevillage;

/**
 * Limpia nombres de donadores de TikTok (emojis/simbolos raros) a algo
 * legible para el aldeano y el cartel. Se puede reajustar luego con /lv house rename.
 */
public final class NameUtil {
    private NameUtil() {}

    public static String clean(String raw) {
        if (raw == null) return "Anon";
        // Deja letras, numeros, espacio, guion y guion bajo. Quita el resto (emojis, etc.)
        String s = raw.replaceAll("[^\\p{L}\\p{N} _\\-]", "").trim();
        // Colapsa espacios multiples
        s = s.replaceAll("\\s+", " ");
        if (s.isEmpty()) s = "Anon";
        if (s.length() > 16) s = s.substring(0, 16).trim();
        return s;
    }
}
