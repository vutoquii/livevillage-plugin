package com.vutocorp.livevillage.comandos;

import com.vutocorp.livevillage.Mascotas;
import com.vutocorp.livevillage.Village;
import com.vutocorp.livevillage.VillageData;
import org.bukkit.command.CommandSender;

/**
 * /lv mob give <pueblo> <donador> <entidad> — prueba directa de Mascotas.soltar()
 * sin pasar por la tabla de regalos (esa es la Fase 4). Sirve para comprobar que una
 * mascota aparece en la casa correcta o en la plaza si el donador no tiene casa.
 */
public final class MobCommand {

    private MobCommand() {}

    public static boolean run(CommandSender sender, String[] args, VillageData datos) {
        if (args.length < 5 || !"give".equalsIgnoreCase(args[1])) {
            sender.sendMessage("Uso: /lv mob give <pueblo> <donador> <entidad>");
            return true;
        }
        Village v = datos.byName(args[2]);
        if (v == null) { sender.sendMessage("No existe el pueblo '" + args[2] + "'."); return true; }

        Mascotas.Resultado r = Mascotas.soltar(v, args[4], args[3]);
        sender.sendMessage(r.ok ? ("OK: " + r.detalle) : ("FALLO: " + r.detalle));
        if (r.ok) datos.guardar();
        return true;
    }
}
