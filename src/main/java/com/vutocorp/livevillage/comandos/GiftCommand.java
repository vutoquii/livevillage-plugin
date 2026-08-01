package com.vutocorp.livevillage.comandos;

import com.vutocorp.livevillage.Donaciones;
import com.vutocorp.livevillage.Village;
import com.vutocorp.livevillage.VillageData;
import org.bukkit.command.CommandSender;

/**
 * /lv gift simulate <pueblo> <donador> <giftId> <monedas> — dispara Donaciones.porRegalo()
 * exactamente como si el regalo hubiera llegado por TikTok, sin necesitar una conexion
 * real. Es la forma de probar el pipeline de donacion completo (regla -> modelo -> casa)
 * sin depender de un directo de verdad.
 */
public final class GiftCommand {

    private GiftCommand() {}

    public static boolean run(CommandSender sender, String[] args, VillageData datos) {
        if (args.length < 6 || !"simulate".equalsIgnoreCase(args[1])) {
            sender.sendMessage("Uso: /lv gift simulate <pueblo> <donador> <giftId> <monedas>");
            return true;
        }
        Village v = datos.byName(args[2]);
        if (v == null) { sender.sendMessage("No existe el pueblo '" + args[2] + "'."); return true; }

        long giftId;
        int monedas;
        try {
            giftId = Long.parseLong(args[4]);
            monedas = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) {
            sender.sendMessage("giftId y monedas tienen que ser numeros.");
            return true;
        }

        Donaciones.Resultado r = Donaciones.porRegalo(v, giftId, "", monedas, args[3]);
        sender.sendMessage(r.ok() ? ("OK: " + r.detalle) : ("FALLO (" + r.estado + "): " + r.detalle));
        if (r.ok()) datos.guardar();
        return true;
    }
}
