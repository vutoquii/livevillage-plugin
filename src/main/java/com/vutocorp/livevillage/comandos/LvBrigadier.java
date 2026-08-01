package com.vutocorp.livevillage.comandos;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.vutocorp.livevillage.Donaciones;
import com.vutocorp.livevillage.House;
import com.vutocorp.livevillage.Perms;
import com.vutocorp.livevillage.Skins;
import com.vutocorp.livevillage.TikTokManager;
import com.vutocorp.livevillage.Village;
import com.vutocorp.livevillage.VillageData;
import com.vutocorp.livevillage.VillageEngine;
import com.vutocorp.livevillage.gui.PuebloGui;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Arbol de comandos /lv con Brigadier: reemplaza el parseo a mano de String[] de
 * LvCommand.java (que se queda como quedo, sin usarse, de referencia del Fase 1-4).
 *
 * No es una copia 1:1 de LvCommands.java del mod (1261 lineas, ~80 hojas): se cubre el
 * camino operativo completo -pueblo, casas, modelos, mascotas, TikTok, regalos, permisos-
 * y quedan fuera del alcance de esta fase los editores en caliente de las tablas de
 * regalo/mascota por comando (gift set id/monedas/tramo, mob set ...) y forceload/ai de
 * chunks: siguen el mismo patron que lo ya escrito aqui, se añaden sin rediseñar nada
 * el dia que hagan falta.
 *
 * Los argumentos hablan en tipos de Brigadier (no String[] a mano), y las sugerencias
 * (pueblos, modelos) salen de los datos reales, no de una lista escrita aparte.
 */
public final class LvBrigadier {

    private final VillageData datos;

    public LvBrigadier(VillageData datos) { this.datos = datos; }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("lv")
            .requires(src -> Perms.puedeUsar(src.getSender(), datos))
            .then(village())
            .then(house())
            .then(model())
            .then(mob())
            .then(tiktok())
            .then(gift())
            .then(perm())
            .then(Commands.literal("gui").executes(this::gui))
            .build();
    }

    // ==================== VILLAGE ====================

    private LiteralArgumentBuilder<CommandSourceStack> village() {
        return Commands.literal("village")
            .then(Commands.literal("create")
                .then(Commands.argument("nombre", StringArgumentType.word())
                    .then(Commands.argument("skin", StringArgumentType.word())
                        .suggests((c, b) -> sugerir(b, Skins.skinNames()))
                        .executes(c -> villageCreate(c, StringArgumentType.getString(c, "skin"))))
                    .executes(c -> villageCreate(c, null))))
            .then(Commands.literal("list").executes(this::villageList))
            .then(Commands.literal("open").then(argPueblo().executes(c -> villageAbrir(c, true))))
            .then(Commands.literal("close").then(argPueblo().executes(c -> villageAbrir(c, false))))
            .then(Commands.literal("setactive").then(argPueblo().executes(this::villageActivar)))
            .then(Commands.literal("delete").then(argPueblo().executes(this::villageBorrar)))
            .then(Commands.literal("tp").then(argPueblo().executes(this::villageTp)));
    }

    private int villageCreate(CommandContext<CommandSourceStack> c, String skinArg) {
        CommandSender sender = c.getSource().getSender();
        String nombre = StringArgumentType.getString(c, "nombre");
        if (datos.byName(nombre) != null) {
            sender.sendMessage("Ya existe un pueblo '" + nombre + "'.");
            return 0;
        }
        String skin = skinArg == null ? com.vutocorp.livevillage.Cfg.DEFAULT_SKIN : skinArg;
        if (Skins.skin(skin) == null) {
            sender.sendMessage("No existe la skin '" + skin + "'. Disponibles: " + String.join(", ", Skins.skinNames()));
            return 0;
        }

        Village v;
        if (sender instanceof Player p) {
            v = new Village(nombre, p.getUniqueId().toString(), p.getWorld().getName(),
                p.getLocation().getBlockX(), p.getLocation().getBlockY() - 1, p.getLocation().getBlockZ());
            v.ownerName = p.getName();
        } else {
            // Consola/RCON: sin dueño (solo lo gestionan admins), en el spawn del primer mundo.
            World w = Bukkit.getWorlds().get(0);
            Location spawn = w.getSpawnLocation();
            v = new Village(nombre, "", w.getName(), spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
        }
        v.skin = skin;
        if (datos.active == null) datos.active = nombre;
        datos.put(v);

        World w = VillageEngine.worldOf(v);
        VillageEngine.buildPlaza(w, v, v.plazaX, v.plazaY, v.plazaZ);
        com.vutocorp.livevillage.PathBuilder.ensureTrunks(w, v);
        datos.guardar();

        sender.sendMessage("Pueblo '" + nombre + "' (skin " + skin + ") creado en "
            + v.plazaX + " " + v.plazaY + " " + v.plazaZ
            + (nombre.equalsIgnoreCase(datos.active) ? " (ahora es el activo)." : "."));
        return 1;
    }

    private int villageList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (datos.villages.isEmpty()) { sender.sendMessage("No hay pueblos todavia."); return 1; }
        for (Village v : datos.villages.values()) {
            sender.sendMessage("- " + v.name + (v.open ? "" : " [cerrado]")
                + " (dueño: " + v.ownerName + ", casas: " + v.houses.size() + ")");
        }
        return 1;
    }

    private int villageAbrir(CommandContext<CommandSourceStack> c, boolean abrir) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (!Perms.exigirGestion(sender, v)) return 0;
        v.open = abrir;
        datos.guardar();
        sender.sendMessage("Pueblo '" + v.name + "' ahora esta " + (abrir ? "abierto" : "cerrado") + " a donadores.");
        return 1;
    }

    private int villageActivar(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        datos.active = v.name;
        datos.guardar();
        sender.sendMessage("'" + v.name + "' es ahora el pueblo activo.");
        return 1;
    }

    private int villageBorrar(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (!Perms.exigirGestion(sender, v)) return 0;
        // Restaurar cada casa antes de olvidar el pueblo: sin esto quedarian huerfanas en
        // el mundo (bloques, aldeanos, mascotas) que nadie vuelve a poder quitar por comando.
        World w = VillageEngine.worldOf(v);
        for (House h : new java.util.ArrayList<>(v.houses)) VillageEngine.restoreAndForget(w, v, h);
        datos.removeVillage(v.name);
        sender.sendMessage("Pueblo '" + v.name + "' borrado (" + v.houses.size() + " casas restauradas). "
            + "La plaza y los caminos NO se han tocado.");
        return 1;
    }

    private int villageTp(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (!(sender instanceof Player p)) { sender.sendMessage("Solo un jugador puede teletransportarse."); return 0; }
        p.teleport(new Location(VillageEngine.worldOf(v), v.plazaX + 0.5, v.plazaY + 1, v.plazaZ + 0.5));
        sender.sendMessage("Teletransportado a la plaza de '" + v.name + "'.");
        return 1;
    }

    // ==================== HOUSE ====================

    private LiteralArgumentBuilder<CommandSourceStack> house() {
        return Commands.literal("house")
            .then(Commands.literal("add")
                .then(argPueblo()
                    .then(Commands.argument("donador", StringArgumentType.word())
                        .then(Commands.argument("modelo", StringArgumentType.word())
                            .suggests((c, b) -> sugerir(b, Skins.modelIds()))
                            .executes(c -> houseAdd(c, StringArgumentType.getString(c, "modelo"))))
                        .executes(c -> houseAdd(c, null)))))
            .then(Commands.literal("remove")
                .then(argPueblo().then(Commands.argument("num", IntegerArgumentType.integer(1))
                    .executes(this::houseRemove))))
            .then(Commands.literal("list").then(argPueblo().executes(this::houseList)))
            .then(Commands.literal("tp")
                .then(argPueblo().then(Commands.argument("num", IntegerArgumentType.integer(1))
                    .executes(this::houseTp))));
    }

    private int houseAdd(CommandContext<CommandSourceStack> c, String modelo) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (!Perms.exigirGestion(sender, v)) return 0;
        String donador = StringArgumentType.getString(c, "donador");

        Donaciones.Resultado r = Donaciones.colocar(v, modelo, donador, true);
        if (!r.ok()) { sender.sendMessage("FALLO (" + r.estado + "): " + r.detalle); return 0; }
        datos.guardar();
        House h = r.casa;
        sender.sendMessage("Casa #" + h.num + " de '" + h.name + "' en (" + h.x + "," + h.y + "," + h.z
            + ") mirando al " + h.facing + ", modelo " + (h.modelId == null ? "provisional" : h.modelId) + ".");
        return 1;
    }

    private int houseRemove(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (!Perms.exigirGestion(sender, v)) return 0;
        int num = IntegerArgumentType.getInteger(c, "num");
        House h = v.byNum(num);
        if (h == null) { sender.sendMessage("El pueblo '" + v.name + "' no tiene casa #" + num + "."); return 0; }
        int n = h.cambios.size();
        VillageEngine.restoreAndForget(VillageEngine.worldOf(v), v, h);
        v.houses.remove(h);
        datos.guardar();
        sender.sendMessage("Casa #" + num + " quitada: " + n + " bloques restaurados.");
        return 1;
    }

    private int houseList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (v.houses.isEmpty()) { sender.sendMessage("'" + v.name + "' no tiene casas todavia."); return 1; }
        for (House h : v.houses) {
            sender.sendMessage("#" + h.num + " " + h.name + " - " + (h.modelId == null ? "provisional" : h.modelId)
                + " - (" + h.x + "," + h.y + "," + h.z + ") - mascotas: " + h.mobs.size());
        }
        return 1;
    }

    private int houseTp(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (!(sender instanceof Player p)) { sender.sendMessage("Solo un jugador puede teletransportarse."); return 0; }
        int num = IntegerArgumentType.getInteger(c, "num");
        House h = v.byNum(num);
        if (h == null) { sender.sendMessage("El pueblo '" + v.name + "' no tiene casa #" + num + "."); return 0; }
        p.teleport(new Location(VillageEngine.worldOf(v), h.x + 0.5, h.floorY(), h.z + 0.5));
        return 1;
    }

    // ==================== MODEL ====================

    private LiteralArgumentBuilder<CommandSourceStack> model() {
        return Commands.literal("model")
            .then(Commands.literal("list").executes(this::modelList))
            .then(Commands.literal("info")
                .then(Commands.argument("modelo", StringArgumentType.word())
                    .suggests((c, b) -> sugerir(b, Skins.modelIds()))
                    .executes(this::modelInfo)));
    }

    private int modelList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        for (Skins.Model m : Skins.models())
            sender.sendMessage("- " + m.id + " (skin " + m.skin + ", " + m.coins + " monedas: " + m.gift + ")");
        return 1;
    }

    private int modelInfo(CommandContext<CommandSourceStack> c) {
        return ModelCommand.run(c.getSource().getSender(),
            new String[]{"model", "info", StringArgumentType.getString(c, "modelo")}) ? 1 : 0;
    }

    // ==================== MOB ====================

    private LiteralArgumentBuilder<CommandSourceStack> mob() {
        return Commands.literal("mob")
            .then(Commands.literal("give")
                .then(argPueblo()
                    .then(Commands.argument("donador", StringArgumentType.word())
                        .then(Commands.argument("entidad", StringArgumentType.word())
                            .executes(this::mobGive)))));
    }

    private int mobGive(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (!Perms.exigirGestion(sender, v)) return 0;
        String donador = StringArgumentType.getString(c, "donador");
        String entidad = StringArgumentType.getString(c, "entidad");
        com.vutocorp.livevillage.Mascotas.Resultado r = com.vutocorp.livevillage.Mascotas.soltar(v, entidad, donador);
        sender.sendMessage(r.ok ? ("OK: " + r.detalle) : ("FALLO: " + r.detalle));
        if (r.ok) datos.guardar();
        return r.ok ? 1 : 0;
    }

    // ==================== TIKTOK ====================

    private LiteralArgumentBuilder<CommandSourceStack> tiktok() {
        return Commands.literal("tiktok")
            .then(Commands.literal("connect")
                .then(Commands.argument("usuario", StringArgumentType.word())
                    .then(argPueblo().executes(this::tiktokConnect))))
            .then(Commands.literal("disconnect").executes(c -> { TikTokManager.desconectar(c.getSource().getSender()); return 1; }))
            .then(Commands.literal("status").executes(c -> { c.getSource().getSender().sendMessage(TikTokManager.estadoTexto()); return 1; }))
            .then(Commands.literal("diag").executes(c -> { TikTokManager.diagnostico(c.getSource().getSender()); return 1; }));
    }

    private int tiktokConnect(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (!Perms.esAdmin(sender)) { sender.sendMessage("Solo un administrador puede conectar TikTok."); return 0; }
        String usuario = StringArgumentType.getString(c, "usuario");
        String pueblo = StringArgumentType.getString(c, "nombre");
        TikTokManager.conectar(sender, usuario, pueblo);
        return 1;
    }

    // ==================== GIFT ====================

    private LiteralArgumentBuilder<CommandSourceStack> gift() {
        return Commands.literal("gift")
            .then(Commands.literal("list").executes(this::giftList))
            .then(Commands.literal("simulate")
                .then(argPueblo()
                    .then(Commands.argument("donador", StringArgumentType.word())
                        .then(Commands.argument("giftId", LongArgumentType.longArg(0))
                            .then(Commands.argument("monedas", IntegerArgumentType.integer(0))
                                .executes(this::giftSimulate))))));
    }

    private int giftList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        var reglas = com.vutocorp.livevillage.Regalos.reglas();
        if (reglas.isEmpty()) { sender.sendMessage("No hay reglas de regalo todavia."); return 1; }
        for (var r : reglas) sender.sendMessage("- " + r.describe() + " -> " + r.modelo);
        return 1;
    }

    private int giftSimulate(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        String donador = StringArgumentType.getString(c, "donador");
        long giftId = LongArgumentType.getLong(c, "giftId");
        int monedas = IntegerArgumentType.getInteger(c, "monedas");
        Donaciones.Resultado r = Donaciones.porRegalo(v, giftId, "", monedas, donador);
        sender.sendMessage(r.ok() ? ("OK: " + r.detalle) : ("FALLO (" + r.estado + "): " + r.detalle));
        if (r.ok()) datos.guardar();
        return r.ok() ? 1 : 0;
    }

    // ==================== PERM ====================

    private LiteralArgumentBuilder<CommandSourceStack> perm() {
        return Commands.literal("perm")
            .then(Commands.literal("grant")
                .then(Commands.argument("jugador", StringArgumentType.word()).executes(c -> permCambiar(c, true))))
            .then(Commands.literal("revoke")
                .then(Commands.argument("jugador", StringArgumentType.word()).executes(c -> permCambiar(c, false))))
            .then(Commands.literal("list").executes(this::permList));
    }

    private int permCambiar(CommandContext<CommandSourceStack> c, boolean conceder) {
        CommandSender sender = c.getSource().getSender();
        if (!Perms.esAdmin(sender)) { sender.sendMessage("Solo un administrador puede dar/quitar permiso."); return 0; }
        String nombre = StringArgumentType.getString(c, "jugador");
        var objetivo = Bukkit.getOfflinePlayer(nombre);
        if (objetivo.getUniqueId() == null) { sender.sendMessage("No encuentro a ese jugador."); return 0; }
        String uuid = objetivo.getUniqueId().toString();
        if (conceder) {
            datos.autorizados.put(uuid, nombre);
            sender.sendMessage("Permiso concedido a " + nombre + ". Puede crear pueblos y manejar los suyos.");
        } else {
            datos.autorizados.remove(uuid);
            sender.sendMessage("Permiso retirado a " + nombre + ".");
        }
        datos.guardar();
        return 1;
    }

    private int permList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (datos.autorizados.isEmpty()) { sender.sendMessage("Nadie tiene permiso concedido a mano."); return 1; }
        for (var e : datos.autorizados.entrySet()) sender.sendMessage("- " + e.getValue() + " (" + e.getKey() + ")");
        return 1;
    }

    // ==================== GUI ====================

    private int gui(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (!(sender instanceof Player p)) { sender.sendMessage("El GUI solo esta disponible para jugadores."); return 0; }
        PuebloGui.abrirListaPueblos(p, datos);
        return 1;
    }

    // ==================== helpers ====================

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> argPueblo() {
        return Commands.argument("nombre", StringArgumentType.word())
            .suggests((c, b) -> sugerir(b, datos.villages.values().stream().map(v -> v.name).toList()));
    }

    private Village pueblo(CommandContext<CommandSourceStack> c) {
        return datos.byName(StringArgumentType.getString(c, "nombre"));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> sugerir(
            com.mojang.brigadier.suggestion.SuggestionsBuilder b, java.util.List<String> opciones) {
        String actual = b.getRemainingLowerCase();
        for (String o : opciones) if (o.toLowerCase().startsWith(actual)) b.suggest(o);
        return b.buildFuture();
    }
}
