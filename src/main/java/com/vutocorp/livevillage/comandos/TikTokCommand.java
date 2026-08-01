package com.vutocorp.livevillage.comandos;

import com.vutocorp.livevillage.TikTokManager;
import org.bukkit.command.CommandSender;

/** /lv tiktok connect|disconnect|status|diag. */
public final class TikTokCommand {

    private TikTokCommand() {}

    public static boolean run(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Uso: /lv tiktok connect <usuario> <pueblo> | disconnect | status | diag");
            return true;
        }
        String sub = args[1];
        if ("status".equalsIgnoreCase(sub)) { sender.sendMessage(TikTokManager.estadoTexto()); return true; }
        if ("diag".equalsIgnoreCase(sub)) { TikTokManager.diagnostico(sender); return true; }
        if ("disconnect".equalsIgnoreCase(sub)) { TikTokManager.desconectar(sender); return true; }
        if ("connect".equalsIgnoreCase(sub)) {
            if (args.length < 4) { sender.sendMessage("Uso: /lv tiktok connect <usuario> <pueblo>"); return true; }
            TikTokManager.conectar(sender, args[2], args[3]);
            return true;
        }
        sender.sendMessage("Subcomando desconocido: " + sub);
        return true;
    }
}
