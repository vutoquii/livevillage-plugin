package com.vutocorp.livevillage;

import com.vutocorp.livevillage.comandos.LvCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LiveVillagePlugin extends JavaPlugin {

    private VillageData datos;

    @Override
    public void onEnable() {
        datos = VillageData.cargar(getDataFolder());
        // Carpeta donde el admin puede pisar una estructura del jar sin recompilar.
        java.io.File estructuras = new java.io.File(getDataFolder(), "structures");
        estructuras.mkdirs();
        Structures.usarCarpeta(estructuras);
        Skins.usarCarpeta(getDataFolder());
        String aviso = Skins.cargar();
        if (aviso != null) getLogger().warning("Catalogo de modelos: " + aviso);
        getCommand("lv").setExecutor(new LvCommand(this, datos));
        getLogger().info("Pueblos cargados: " + datos.villages.size()
            + ", modelos: " + Skins.modelIds().size());
    }

    @Override
    public void onDisable() {
        if (datos != null) datos.guardar();
    }

    public VillageData datos() { return datos; }
}
