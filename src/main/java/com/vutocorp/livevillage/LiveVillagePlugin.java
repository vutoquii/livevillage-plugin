package com.vutocorp.livevillage;

import com.vutocorp.livevillage.comandos.LvCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LiveVillagePlugin extends JavaPlugin {

    private VillageData datos;

    @Override
    public void onEnable() {
        datos = VillageData.cargar(getDataFolder());
        getCommand("lv").setExecutor(new LvCommand(this, datos));
        getLogger().info("Pueblos cargados: " + datos.villages.size());
    }

    @Override
    public void onDisable() {
        if (datos != null) datos.guardar();
    }

    public VillageData datos() { return datos; }
}
