package com.vutocorp.livevillage;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Quien puede usar /lv. Calco de Perms.java del mod, pero mas simple: en
 * Bukkit los "niveles de op" de Compat.tieneNivelOp no existen, hay permisos
 * con nombre (registrados en plugin.yml). "Admin" pasa a ser el permiso
 * livevillage.admin (los operadores del server lo tienen por defecto).
 */
public final class Perms {

    private Perms() {}

    public static final String PERM_ADMIN = "livevillage.admin";

    public static boolean esAdmin(CommandSender src) {
        return src.hasPermission(PERM_ADMIN) || src.isOp();
    }

    /** UUID de quien ejecuta, o null si es la consola/RCON. */
    public static UUID uuidDe(CommandSender src) {
        return src instanceof Player p ? p.getUniqueId() : null;
    }

    public static boolean puedeUsar(CommandSender src, VillageData data) {
        if (esAdmin(src)) return true;
        UUID id = uuidDe(src);
        return id != null && data.autorizados.containsKey(id.toString());
    }

    public static boolean puedeGestionar(CommandSender src, Village v) {
        if (esAdmin(src)) return true;
        UUID id = uuidDe(src);
        return id != null && v != null && id.toString().equals(v.owner);
    }

    public static boolean exigirGestion(CommandSender src, Village v) {
        if (puedeGestionar(src, v)) return true;
        src.sendMessage("El pueblo '" + v.name + "' no es tuyo. Solo su dueño o un administrador pueden tocarlo.");
        return false;
    }
}
