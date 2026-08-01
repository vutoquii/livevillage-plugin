package com.vutocorp.livevillage.comandos;

import com.vutocorp.livevillage.Donaciones;
import com.vutocorp.livevillage.House;
import com.vutocorp.livevillage.Village;
import com.vutocorp.livevillage.VillageData;
import com.vutocorp.livevillage.VillageEngine;
import org.bukkit.command.CommandSender;

/**
 * /lv house add|remove — colocar y quitar casas a mano. Delegan en Donaciones.colocar()
 * con saltarRitmo=true: un admin no tiene por que esperar el cooldown que existe para
 * frenar una racha de regalos, igual que en el mod.
 */
public final class HouseCommand {

    private HouseCommand() {}

    public static boolean run(CommandSender sender, String[] args, VillageData datos) {
        if (args.length < 2) {
            sender.sendMessage("Uso: /lv house add <pueblo> <donador> [modelo] | /lv house remove <pueblo> <num>");
            return true;
        }
        if ("add".equalsIgnoreCase(args[1])) return add(sender, args, datos);
        if ("remove".equalsIgnoreCase(args[1])) return remove(sender, args, datos);
        sender.sendMessage("Subcomando desconocido: " + args[1]);
        return true;
    }

    private static boolean add(CommandSender sender, String[] args, VillageData datos) {
        if (args.length < 4) { sender.sendMessage("Uso: /lv house add <pueblo> <donador> [modelo]"); return true; }
        Village v = datos.byName(args[2]);
        if (v == null) { sender.sendMessage("No existe el pueblo '" + args[2] + "'."); return true; }

        String donador = args[3];
        String modelId = args.length >= 5 ? args[4] : null;

        Donaciones.Resultado r = Donaciones.colocar(v, modelId, donador, true);
        if (!r.ok()) { sender.sendMessage("FALLO: " + r.detalle); return true; }
        datos.guardar();

        House h = r.casa;
        sender.sendMessage("Casa #" + h.num + " de '" + h.name + "' en ("
            + h.x + "," + h.y + "," + h.z + ") mirando al " + h.facing
            + ", modelo " + (h.modelId == null ? "provisional" : h.modelId)
            + ", " + h.cambios.size() + " bloques registrados.");
        return true;
    }

    private static boolean remove(CommandSender sender, String[] args, VillageData datos) {
        if (args.length < 4) { sender.sendMessage("Uso: /lv house remove <pueblo> <num>"); return true; }
        Village v = datos.byName(args[2]);
        if (v == null) { sender.sendMessage("No existe el pueblo '" + args[2] + "'."); return true; }
        int num;
        try { num = Integer.parseInt(args[3]); }
        catch (NumberFormatException e) { sender.sendMessage("'" + args[3] + "' no es un numero."); return true; }

        House h = v.byNum(num);
        if (h == null) { sender.sendMessage("El pueblo '" + v.name + "' no tiene casa #" + num + "."); return true; }

        int n = h.cambios.size();
        VillageEngine.restoreAndForget(VillageEngine.worldOf(v), v, h);
        v.houses.remove(h);
        datos.guardar();
        sender.sendMessage("Casa #" + num + " quitada: " + n + " bloques restaurados.");
        return true;
    }
}
