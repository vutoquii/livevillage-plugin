package com.vutocorp.livevillage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistencia de los pueblos. Equivalente al VillageData del mod (que ahi es
 * un SavedData de NeoForge); aqui no existe ese mecanismo asi que se guarda a
 * mano en plugins/LiveVillage/villages.yml. Se elige YAML (no el JSON que usa
 * Regalos/Skins) porque es el formato nativo de configuracion de Bukkit y lo
 * puede tocar un admin a mano sin herramientas aparte.
 *
 * Sin autosave periodico por ahora: se escribe a disco en cada mutacion
 * (setDirty -> guardar ya). El volumen de escrituras (crear/borrar pueblo o
 * casa) es bajo; si en fases posteriores esto se nota, se puede pasar a un
 * guardado diferido cada N segundos como hace el mod.
 */
public final class VillageData {

    private final File fichero;
    public final Map<String, Village> villages = new LinkedHashMap<>();
    public String active = null;
    public final Map<String, String> autorizados = new LinkedHashMap<>();

    private VillageData(File fichero) { this.fichero = fichero; }

    public static VillageData cargar(File carpetaDatos) {
        File f = new File(carpetaDatos, "villages.yml");
        VillageData d = new VillageData(f);
        if (!f.exists()) return d;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection villages = yaml.getConfigurationSection("villages");
        if (villages != null) {
            for (String key : villages.getKeys(false)) {
                Map<String, Object> m = villages.getConfigurationSection(key).getValues(true);
                d.villages.put(key.toLowerCase(), Village.fromMap(key, m));
            }
        }
        d.active = yaml.getString("active", null);
        ConfigurationSection perms = yaml.getConfigurationSection("autorizados");
        if (perms != null) for (String uuid : perms.getKeys(false)) d.autorizados.put(uuid, perms.getString(uuid, ""));
        return d;
    }

    public void guardar() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Village> e : villages.entrySet()) {
            yaml.createSection("villages." + e.getKey(), e.getValue().toMap());
        }
        if (active != null) yaml.set("active", active);
        for (Map.Entry<String, String> e : autorizados.entrySet()) {
            yaml.set("autorizados." + e.getKey(), e.getValue());
        }
        try {
            fichero.getParentFile().mkdirs();
            yaml.save(fichero);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo guardar villages.yml", e);
        }
    }

    // ---- Helpers, igual que el mod ----
    public Village byName(String name) { return villages.get(name.toLowerCase()); }

    public Village activeVillage() { return active == null ? null : villages.get(active.toLowerCase()); }

    public void put(Village v) {
        villages.put(v.name.toLowerCase(), v);
        guardar();
    }

    public boolean rename(Village v, String nuevo) {
        String clave = nuevo.toLowerCase();
        if (villages.containsKey(clave)) return false;
        boolean eraActivo = active != null && active.equalsIgnoreCase(v.name);
        villages.remove(v.name.toLowerCase());
        v.name = nuevo;
        villages.put(clave, v);
        if (eraActivo) active = nuevo;
        guardar();
        return true;
    }

    public void removeVillage(String name) {
        villages.remove(name.toLowerCase());
        if (active != null && active.equalsIgnoreCase(name)) active = null;
        guardar();
    }
}
