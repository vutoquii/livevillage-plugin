package com.vutocorp.livevillage.comandos;

import com.vutocorp.livevillage.Cfg;
import com.vutocorp.livevillage.House;
import com.vutocorp.livevillage.NameUtil;
import com.vutocorp.livevillage.Skins;
import com.vutocorp.livevillage.Structures;
import com.vutocorp.livevillage.Village;
import com.vutocorp.livevillage.VillageData;
import com.vutocorp.livevillage.VillageEngine;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * /lv house add|remove — colocar y quitar casas.
 *
 * Es la version de Donaciones.colocar() del mod sin la parte de ritmo ni de regalos:
 * eso llega en la Fase 4. Aqui solo interesa que la casa se coloque bien.
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

        World w = VillageEngine.worldOf(v);
        String donador = args[3];
        String modelId = args.length >= 5 ? args[4] : null;

        Skins.Model model = Skins.model(modelId);
        if (modelId != null && model == null) {
            sender.sendMessage("No existe el modelo '" + modelId + "'.");
            return true;
        }
        if (model == null) model = Skins.cheapest(v.skin == null ? Cfg.DEFAULT_SKIN : v.skin);

        // La plaza tiene que existir antes que la primera casa: es de donde salen las avenidas.
        if (v.houses.isEmpty() && v.pathCells.isEmpty())
            VillageEngine.buildPlaza(w, v, v.plazaX, v.plazaY, v.plazaZ);

        // Huella real del modelo: sin saber aun la rotacion se reserva la medida mayor.
        int hx = Cfg.PLOT_HALF, hz = Cfg.PLOT_HALF;
        if (model != null) {
            Structures.Info info = Structures.info(model.structure);
            if (info != null) { hx = Math.max(info.sizeX, info.sizeZ) / 2; hz = hx; }
            else model = null;                                  // sin .nbt: cabaña provisional
        }

        int[] flat = VillageEngine.findSpot(w, v, hx, hz);
        if (flat == null) { sender.sendMessage("No encontre sitio libre y seco en '" + v.name + "'."); return true; }
        int groundY = VillageEngine.averageGroundY(w, flat[0], flat[1], hx, hz);

        String limpio = NameUtil.clean(donador);
        House h = new House(donador, limpio, flat[0], groundY, flat[1], "north");
        h.num = ++v.nextNum;
        if (model != null) h.modelId = model.id;
        v.houses.add(h);          // registrar ANTES: asi su propio camino la respeta

        VillageEngine.buildHouse(w, v, h, flat[0], groundY, flat[1]);
        datos.guardar();

        sender.sendMessage("Casa #" + h.num + " de '" + limpio + "' en ("
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
