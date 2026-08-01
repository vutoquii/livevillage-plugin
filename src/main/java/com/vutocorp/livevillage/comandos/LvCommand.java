package com.vutocorp.livevillage.comandos;

import com.vutocorp.livevillage.LiveVillagePlugin;
import com.vutocorp.livevillage.PathBuilder;
import com.vutocorp.livevillage.Perms;
import com.vutocorp.livevillage.Village;
import com.vutocorp.livevillage.VillageData;
import com.vutocorp.livevillage.VillageEngine;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Arbol de comandos MINIMO para la Fase 1: crear pueblos y listarlos, y que
 * persistan entre reinicios. El arbol completo (Brigadier, paridad con
 * LvCommands.java del mod) es la Fase 5 del roadmap.
 */
public final class LvCommand implements CommandExecutor {

    private final LiveVillagePlugin plugin;
    private final VillageData datos;

    public LvCommand(LiveVillagePlugin plugin, VillageData datos) {
        this.plugin = plugin;
        this.datos = datos;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!Perms.puedeUsar(sender, datos)) {
            sender.sendMessage("No tienes permiso para usar /lv.");
            return true;
        }
        if (args.length >= 2 && "village".equalsIgnoreCase(args[0])) {
            return village(sender, args);
        }
        if (args.length >= 2 && "model".equalsIgnoreCase(args[0])) {
            return ModelCommand.run(sender, args);
        }
        if (args.length >= 2 && "house".equalsIgnoreCase(args[0])) {
            return HouseCommand.run(sender, args, datos);
        }
        if (args.length >= 2 && "mob".equalsIgnoreCase(args[0])) {
            return MobCommand.run(sender, args, datos);
        }
        sender.sendMessage("Uso: /lv village create <nombre> | /lv village list");
        sender.sendMessage("     /lv model info <modelo> | /lv model test <modelo> [rot]");
        sender.sendMessage("     /lv house add <pueblo> <donador> [modelo] | /lv house remove <pueblo> <num>");
        return true;
    }

    private boolean village(CommandSender sender, String[] args) {
        String sub = args[1];
        if ("list".equalsIgnoreCase(sub)) {
            if (datos.villages.isEmpty()) { sender.sendMessage("No hay pueblos todavia."); return true; }
            for (Village v : datos.villages.values()) {
                sender.sendMessage("- " + v.name + " (dueño: " + v.ownerName + ", casas: " + v.houses.size() + ")");
            }
            return true;
        }
        if ("create".equalsIgnoreCase(sub)) {
            if (args.length < 3) { sender.sendMessage("Uso: /lv village create <nombre>"); return true; }
            String nombre = args[2];
            if (datos.byName(nombre) != null) { sender.sendMessage("Ya existe un pueblo '" + nombre + "'."); return true; }

            Village v;
            if (sender instanceof Player p) {
                v = new Village(nombre, p.getUniqueId().toString(), p.getWorld().getName(),
                    p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ());
                v.ownerName = p.getName();
            } else {
                // Consola/RCON: igual que en el mod, sin dueño (owner vacio) = solo admins lo
                // gestionan. Sin jugador no hay "donde estoy parado", asi que pide world+x+z a
                // mano PERO calcula la Y del terreno real: aceptar una Y a mano fue justamente
                // el error que produjo pueblos con la plaza a 7 bloques de la altura real del
                // suelo (los arboles se rechazaban todos por "desnivel" al comprobarlo en juego).
                if (args.length < 6) {
                    sender.sendMessage("Desde consola: /lv village create <nombre> <world> <x> <z>");
                    return true;
                }
                World w = Bukkit.getWorld(args[3]);
                if (w == null) { sender.sendMessage("No existe el mundo '" + args[3] + "'."); return true; }
                int x = Integer.parseInt(args[4]), z = Integer.parseInt(args[5]);
                int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                v = new Village(nombre, "", args[3], x, y, z);
            }
            datos.put(v);

            // La plaza y las 4 avenidas se levantan AQUI, no al añadir la primera casa: es
            // lo que hace /lv village create en el mod (LvCommands.createVillage), y sin esto
            // el comando solo registra datos sin poner un solo bloque, lo que confunde a
            // cualquiera que espere ver algo aparecer donde estaba parado.
            World w = VillageEngine.worldOf(v);
            VillageEngine.buildPlaza(w, v, v.plazaX, v.plazaY, v.plazaZ);
            PathBuilder.ensureTrunks(w, v);
            datos.guardar();

            sender.sendMessage("Pueblo '" + nombre + "' creado en " + v.world + " ("
                + v.plazaX + "," + v.plazaY + "," + v.plazaZ + "). Guardado.");
            return true;
        }
        sender.sendMessage("Subcomando desconocido: " + sub);
        return true;
    }
}
