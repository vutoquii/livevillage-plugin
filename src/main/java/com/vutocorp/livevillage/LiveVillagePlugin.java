package com.vutocorp.livevillage;

import com.vutocorp.livevillage.comandos.LvCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public final class LiveVillagePlugin extends JavaPlugin {

    /** Acceso estatico al logger para clases que no tienen la instancia del plugin
     *  a mano (Villagers, VillageEngine). Se asigna en onEnable(). */
    public static Logger LOGGER;

    private VillageData datos;

    @Override
    public void onEnable() {
        LOGGER = getLogger();
        datos = VillageData.cargar(getDataFolder());
        // Carpeta donde el admin puede pisar una estructura del jar sin recompilar.
        java.io.File estructuras = new java.io.File(getDataFolder(), "structures");
        estructuras.mkdirs();
        Structures.usarCarpeta(estructuras);
        Skins.usarCarpeta(getDataFolder());
        String aviso = Skins.cargar();
        if (aviso != null) getLogger().warning("Catalogo de modelos: " + aviso);
        Regalos.usarCarpeta(getDataFolder());
        String avisoRegalos = Regalos.cargar();
        if (avisoRegalos != null) getLogger().warning("Tabla de regalos: " + avisoRegalos);
        Mascotas.usarCarpeta(getDataFolder());
        String avisoMascotas = Mascotas.cargar();
        if (avisoMascotas != null) getLogger().warning("Tabla de mascotas: " + avisoMascotas);
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
