package com.vutocorp.livevillage.comandos;

import com.vutocorp.livevillage.LiveVillagePlugin;
import com.vutocorp.livevillage.Perms;
import com.vutocorp.livevillage.Village;
import com.vutocorp.livevillage.VillageData;
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
        sender.sendMessage("Uso: /lv village create <nombre> | /lv village list");
        sender.sendMessage("     /lv model info <modelo> | /lv model test <modelo> [rot]");
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
                // Consola/RCON: igual que en el mod, sin dueño (owner vacio) = solo admins lo gestionan.
                // Sin jugador no hay "donde estoy parado", asi que exige world+x+y+z a mano.
                if (args.length < 7) {
                    sender.sendMessage("Desde consola: /lv village create <nombre> <world> <x> <y> <z>");
                    return true;
                }
                v = new Village(nombre, "", args[3],
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]));
            }
            datos.put(v);
            sender.sendMessage("Pueblo '" + nombre + "' creado en " + v.world + " ("
                + v.plazaX + "," + v.plazaY + "," + v.plazaZ + "). Guardado.");
            return true;
        }
        sender.sendMessage("Subcomando desconocido: " + sub);
        return true;
    }
}
