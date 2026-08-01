package com.vutocorp.livevillage.comandos;

import com.vutocorp.livevillage.Cfg;
import com.vutocorp.livevillage.House;
import com.vutocorp.livevillage.Pos3;
import com.vutocorp.livevillage.Structures;
import org.bukkit.World;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;

/**
 * /lv model info|test — equivalente al /lv model info del mod.
 *
 * "test" existe para comprobar UNA cosa concreta que no se puede dar por sentada:
 * que Structure.place() de Bukkit use el mismo pivote de rotacion que el
 * placeInWorld() de vanilla, del que salen las cuentas de Structures.originFor().
 * Pega la casa rotada y compara donde CREE el codigo que quedo la marca de oro
 * con donde esta de verdad. Si no cuadran, toda la colocacion de la Fase 2 esta
 * mal y hay que saberlo aqui, no cuando el pueblo salga torcido.
 */
public final class ModelCommand {

    private ModelCommand() {}

    public static boolean run(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Uso: /lv model info <modelo> | /lv model test <modelo> [rot]");
            return true;
        }
        String sub = args[1];

        if ("info".equalsIgnoreCase(sub)) {
            if (args.length < 3) { sender.sendMessage("Uso: /lv model info <modelo>"); return true; }
            return info(sender, args[2]);
        }
        if ("test".equalsIgnoreCase(sub)) {
            if (args.length < 3) {
                sender.sendMessage("Uso: /lv model test <modelo> [0|90|180|270]");
                sender.sendMessage("     desde consola: /lv model test <modelo> <rot> <world> <x> <y> <z>");
                return true;
            }
            int grados = args.length >= 4 ? parseInt(args[3]) : 90;
            return test(sender, args[2], grados, args);
        }
        sender.sendMessage("Subcomando desconocido: " + sub);
        return true;
    }

    private static boolean info(CommandSender sender, String modelo) {
        Structures.Info i = Structures.info(modelo);
        if (i == null) { sender.sendMessage("No encuentro el modelo '" + modelo + "'."); return true; }
        sender.sendMessage("Modelo '" + modelo + "' (" + Structures.source(modelo) + ")");
        sender.sendMessage("  tamano: " + i.sizeX + "x" + i.sizeY + "x" + i.sizeZ);
        sender.sendMessage("  fachada: " + (i.front == null ? "sin fachada (no rota)" : i.front));
        sender.sendMessage("  puerta local: " + (i.doorX < 0 ? "NO HAY" : "(" + i.doorX + "," + i.doorZ + ")"));
        sender.sendMessage("  marcas -> aldeano:" + si(i.hasVillager)
                         + " trabajo:" + si(i.hasJob) + " cartel:" + si(i.hasSign));
        if (!i.hasVillager || !i.hasJob || !i.hasSign)
            sender.sendMessage("  AVISO: faltan marcas. Toda casa debe llevar las tres.");
        if (i.doorX < 0)
            sender.sendMessage("  AVISO: sin puerta de verdad la fachada se decide por el lapis, que se equivoca.");
        return true;
    }

    /** Pega la estructura rotada y comprueba que la marca de oro cae donde predice el codigo. */
    private static boolean test(CommandSender sender, String modelo, int grados, String[] args) {
        Structures.Info i = Structures.info(modelo);
        Structure tpl = Structures.cargar(modelo);
        if (i == null || tpl == null) { sender.sendMessage("No encuentro el modelo '" + modelo + "'."); return true; }

        StructureRotation rot = switch (grados) {
            case 90  -> StructureRotation.CLOCKWISE_90;
            case 180 -> StructureRotation.CLOCKWISE_180;
            case 270 -> StructureRotation.COUNTERCLOCKWISE_90;
            default  -> StructureRotation.NONE;
        };

        World w;
        int minX, minZ, baseY;
        if (sender instanceof Player p) {
            w = p.getWorld();
            minX = p.getLocation().getBlockX() + 5;
            minZ = p.getLocation().getBlockZ() + 5;
            baseY = p.getLocation().getBlockY();
        } else {
            // Desde consola no hay "donde estoy": hace falta world y coordenadas a mano.
            if (args.length < 8) {
                sender.sendMessage("Desde consola: /lv model test <modelo> <rot> <world> <x> <y> <z>");
                return true;
            }
            w = org.bukkit.Bukkit.getWorld(args[4]);
            if (w == null) { sender.sendMessage("No existe el mundo '" + args[4] + "'."); return true; }
            minX = parseInt(args[5]);
            baseY = parseInt(args[6]);
            minZ = parseInt(args[7]);
        }

        int fw = Structures.footprintX(rot, i.sizeX, i.sizeZ);
        int fd = Structures.footprintZ(rot, i.sizeX, i.sizeZ);
        int[] origin = Structures.originFor(rot, i.sizeX, i.sizeZ, minX, minZ);

        House dummy = new House();
        Structures.paste(w, dummy, tpl, rot, origin[0], origin[1], minX, baseY, minZ, fw, i.sizeY, fd);

        sender.sendMessage("Pegado '" + modelo + "' rot=" + grados + " en minXZ=(" + minX + "," + minZ + ")");
        sender.sendMessage("  huella rotada: " + fw + "x" + fd + "   origen place(): (" + origin[0] + "," + origin[1] + ")");
        sender.sendMessage("  bloques cambiados registrados: " + dummy.cambios.size());

        // Se comprueban LAS TRES marcas, no solo una: en japonhouse1 el oro cae en el
        // centro exacto (4,4) de una huella 9x9, y el centro rota sobre si mismo. Con esa
        // marca sola el test pasa aunque la rotacion este mal. El lapis (6,1) si es
        // asimetrico y delata cualquier error de pivote.
        boolean todoOk = true;
        todoOk &= comprobar(sender, w, "oro   ", Cfg.MARK_VILLAGER, i.markVillager,
                            rot, i, minX, baseY, minZ, fw, fd);
        todoOk &= comprobar(sender, w, "esmer.", Cfg.MARK_JOB, i.markJob,
                            rot, i, minX, baseY, minZ, fw, fd);
        todoOk &= comprobar(sender, w, "lapis ", Cfg.MARK_SIGN, i.markSign,
                            rot, i, minX, baseY, minZ, fw, fd);

        sender.sendMessage(todoOk
            ? "  RESULTADO: rotacion OK, el pivote de Bukkit coincide con el calculo."
            : "  RESULTADO: NO CUADRA. Revisar originFor()/rotXZ().");
        return true;
    }

    /** Compara donde predice el codigo que cae una marca contra donde cayo de verdad. */
    private static boolean comprobar(CommandSender sender, org.bukkit.World w, String etiqueta,
                                     org.bukkit.Material mat, Pos3 local, StructureRotation rot,
                                     Structures.Info i, int minX, int baseY, int minZ, int fw, int fd) {
        if (local == null) return true;                      // esa marca no esta en el modelo
        int[] pred = Structures.worldXZ(rot, i.sizeX, i.sizeZ, minX, minZ, local.x, local.z);
        Pos3 real = Structures.findMark(w, mat, minX, baseY, minZ, fw, i.sizeY, fd);
        if (real == null) {
            sender.sendMessage("  " + etiqueta + " FALLO: no aparece dentro de la huella calculada.");
            return false;
        }
        boolean ok = real.x == pred[0] && real.z == pred[1];
        sender.sendMessage("  " + etiqueta + " local(" + local.x + "," + local.z + ")"
            + " predicho(" + pred[0] + "," + pred[1] + ")"
            + " real(" + real.x + "," + real.z + ") " + (ok ? "OK" : "<<< NO CUADRA"));
        return ok;
    }

    private static String si(boolean b) { return b ? "si" : "NO"; }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
