package com.vutocorp.livevillage;

import com.vutocorp.livevillage.comandos.LvBrigadier;
import com.vutocorp.livevillage.gui.PuebloGui;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
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
        ConexionTikTok.usarCarpeta(getDataFolder().toPath());
        TikTokManager.init(datos);

        LvBrigadier arbol = new LvBrigadier(datos);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(arbol.build(), "Comandos de LiveVillage"));
        Bukkit.getPluginManager().registerEvents(new PuebloGui(), this);

        // Pausa de IA: /lv village ai solo cambia v.aiPause, esto es lo que de verdad
        // congela/despierta aldeanos cada AI_CHECK_INTERVAL ticks. Igual que el mod
        // (LiveVillage.onServerTick), pero con el scheduler de Bukkit en vez de un evento
        // de NeoForge.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Villagers.lastActive = 0;
            Villagers.lastPaused = 0;
            for (Village v : datos.villages.values()) {
                org.bukkit.World w = VillageEngine.worldOf(v);
                if (w != null) Villagers.tickAiPause(w, v);
            }
        }, Cfg.AI_CHECK_INTERVAL, Cfg.AI_CHECK_INTERVAL);

        getLogger().info("Pueblos cargados: " + datos.villages.size()
            + ", modelos: " + Skins.modelIds().size());
    }

    @Override
    public void onDisable() {
        TikTokManager.desconectar(Bukkit.getConsoleSender());
        if (datos != null) datos.guardar();
    }

    public VillageData datos() { return datos; }
}
