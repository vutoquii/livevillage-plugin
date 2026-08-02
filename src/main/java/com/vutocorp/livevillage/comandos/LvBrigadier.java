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
 * incluyendo los editores en caliente de las tablas de regalo/mascota (gift set
 * id/monedas/tramo/nombre, mob set ...). Queda fuera del alcance de esta fase el
 * forceload/ai de chunks: sigue el mismo patron que lo ya escrito aqui, se añade sin
 * rediseñar nada el dia que haga falta.
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
            .then(Commands.literal("tp").then(argPueblo().executes(this::villageTp)))
            .then(Commands.literal("ai")
                .then(argPueblo()
                    .then(Commands.literal("on").executes(c -> villageAi(c, true)))
                    .then(Commands.literal("off").executes(c -> villageAi(c, false)))
                    .then(Commands.literal("status").executes(this::villageAiStatus))))
            .then(Commands.literal("forceload")
                .then(argPueblo()
                    .then(Commands.literal("on").executes(c -> villageForceload(c, true)))
                    .then(Commands.literal("off").executes(c -> villageForceload(c, false)))
                    .then(Commands.literal("status").executes(this::villageForceStatus))));
    }

    private int villageAi(CommandContext<CommandSourceStack> c, boolean on) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (!Perms.exigirGestion(sender, v)) return 0;
        v.aiPause = on;
        datos.guardar();
        if (!on) {
            World w = VillageEngine.worldOf(v);
            for (House h : v.houses) com.vutocorp.livevillage.Villagers.wake(w, h);
        }
        sender.sendMessage("Pausa de IA " + (on ? "ACTIVADA" : "DESACTIVADA") + " en '" + v.name + "'."
            + (on ? " Se congelan los aldeanos a mas de " + com.vutocorp.livevillage.Cfg.AI_ACTIVE_RADIUS
                    + " bloques de cualquier jugador, a partir de " + com.vutocorp.livevillage.Cfg.AI_PAUSE_MIN_HOUSES + " casas."
                  : " Todos los aldeanos cargados vuelven a moverse."));
        return 1;
    }

    private int villageAiStatus(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        World w = VillageEngine.worldOf(v);
        int[] cnt = com.vutocorp.livevillage.Villagers.countAi(w, v);
        boolean activa = v.aiPause && v.houses.size() >= com.vutocorp.livevillage.Cfg.AI_PAUSE_MIN_HOUSES;
        sender.sendMessage("Pueblo '" + v.name + "'  pausa de IA: " + (v.aiPause ? "ON" : "OFF")
            + (v.aiPause && !activa ? " (aun sin efecto: " + v.houses.size() + " casas, hace falta "
                + com.vutocorp.livevillage.Cfg.AI_PAUSE_MIN_HOUSES + ")" : ""));
        sender.sendMessage("  aldeanos moviendose: " + cnt[0] + "   congelados: " + cnt[1]
            + "   en chunk descargado: " + cnt[2] + "   sin aldeano: " + cnt[3]);
        return 1;
    }

    private int villageForceload(CommandContext<CommandSourceStack> c, boolean on) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        if (!Perms.exigirGestion(sender, v)) return 0;
        World w = VillageEngine.worldOf(v);
        int total = com.vutocorp.livevillage.Chunks.countFor(v);
        int n = com.vutocorp.livevillage.Chunks.apply(w, v, on);
        v.forced = on;
        datos.guardar();
        if (!on) {
            sender.sendMessage("Chunks del pueblo '" + v.name + "' DESCARGADOS");
            sender.sendMessage("  " + n + " chunks liberados. El pueblo vuelve a reposo: se cargaran "
                + "solos cuando alguien se acerque o entre una donacion.");
            return 1;
        }
        sender.sendMessage("Chunks del pueblo '" + v.name + "' CARGADOS");
        sender.sendMessage("  " + n + " chunks fijados en memoria"
            + (n < total ? " (de " + total + ": se han fijado los mas cercanos a la plaza)" : "")
            + ". El pueblo sigue vivo aunque no haya nadie: aldeanos moviendose, cultivos creciendo.");
        sender.sendMessage("  Consume TPS mientras este activo: acuerdate de apagarlo al terminar el "
            + "directo con /lv village forceload " + v.name + " off");
        return 1;
    }

    private int villageForceStatus(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Village v = pueblo(c);
        if (v == null) { sender.sendMessage("No existe ese pueblo."); return 0; }
        sender.sendMessage("Pueblo '" + v.name + "'  forceload: " + (v.forced ? "ON" : "OFF")
            + "  (" + com.vutocorp.livevillage.Chunks.countFor(v) + " chunks cubririan el pueblo entero)");
        return 1;
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
            .then(Commands.literal("list").executes(this::mobList))
            .then(Commands.literal("give")
                .then(argPueblo()
                    .then(Commands.argument("donador", StringArgumentType.word())
                        .then(Commands.argument("entidad", StringArgumentType.word())
                            .executes(this::mobGive)))))
            .then(Commands.literal("remove")
                .then(Commands.argument("numero", IntegerArgumentType.integer(1))
                    .executes(this::mobRemove)))
            .then(Commands.literal("set")
                .then(Commands.literal("id")
                    .then(Commands.argument("giftId", LongArgumentType.longArg(1))
                        .then(Commands.argument("mob", StringArgumentType.word())
                            .suggests((c, b) -> sugerir(b, com.vutocorp.livevillage.Mascotas.idsSugeridos()))
                            .executes(c -> mobSet(c, LongArgumentType.getLong(c, "giftId"), null, 0, 0, 0,
                                StringArgumentType.getString(c, "mob"))))))
                .then(Commands.literal("nombre")
                    .then(Commands.argument("regalo", StringArgumentType.string())
                        .then(Commands.argument("mob", StringArgumentType.word())
                            .suggests((c, b) -> sugerir(b, com.vutocorp.livevillage.Mascotas.idsSugeridos()))
                            .executes(c -> mobSet(c, 0, StringArgumentType.getString(c, "regalo"), 0, 0, 0,
                                StringArgumentType.getString(c, "mob"))))))
                .then(Commands.literal("monedas")
                    .then(Commands.argument("monedas", IntegerArgumentType.integer(1))
                        .then(Commands.argument("mob", StringArgumentType.word())
                            .suggests((c, b) -> sugerir(b, com.vutocorp.livevillage.Mascotas.idsSugeridos()))
                            .executes(c -> mobSet(c, 0, null, IntegerArgumentType.getInteger(c, "monedas"), 0, 0,
                                StringArgumentType.getString(c, "mob"))))))
                .then(Commands.literal("tramo")
                    .then(Commands.argument("min", IntegerArgumentType.integer(0))
                        .then(Commands.argument("max", IntegerArgumentType.integer(0))
                            .then(Commands.argument("mob", StringArgumentType.word())
                                .suggests((c, b) -> sugerir(b, com.vutocorp.livevillage.Mascotas.idsSugeridos()))
                                .executes(c -> mobSet(c, 0, null, 0,
                                    IntegerArgumentType.getInteger(c, "min"),
                                    IntegerArgumentType.getInteger(c, "max"),
                                    StringArgumentType.getString(c, "mob"))))))));
    }

    private int mobList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        var reglas = com.vutocorp.livevillage.Mascotas.reglas();
        if (reglas.isEmpty()) { sender.sendMessage("No hay reglas de mascota todavia."); return 1; }
        int i = 1;
        for (var r : reglas) sender.sendMessage("  " + (i++) + ". " + r.describe() + "  ->  " + r.mob);
        return 1;
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

    private int mobSet(CommandContext<CommandSourceStack> c, long giftId, String nombre,
                       int monedas, int min, int max, String mob) {
        CommandSender sender = c.getSource().getSender();
        if (!com.vutocorp.livevillage.Mascotas.existeMob(mob)) {
            sender.sendMessage("No existe la entidad '" + mob + "'.");
            return 0;
        }
        var r = new com.vutocorp.livevillage.Mascotas.Regla();
        r.giftId = giftId; r.nombre = nombre; r.monedas = monedas;
        r.monedasMin = min; r.monedasMax = max; r.mob = mob;
        com.vutocorp.livevillage.Mascotas.poner(r);
        try { com.vutocorp.livevillage.Mascotas.guardar(); }
        catch (Exception e) { sender.sendMessage("Regla aplicada, pero no pude guardarla en el fichero: " + e.getMessage()); }
        sender.sendMessage("Regla: " + r.describe() + "  ->  " + mob);
        return 1;
    }

    private int mobRemove(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        int numero = IntegerArgumentType.getInteger(c, "numero");
        var reglas = com.vutocorp.livevillage.Mascotas.reglas();
        if (numero < 1 || numero > reglas.size()) {
            sender.sendMessage("Numero fuera de rango (1.." + reglas.size() + "). Mira /lv mob list.");
            return 0;
        }
        var r = reglas.get(numero - 1);
        com.vutocorp.livevillage.Mascotas.quitar(numero);
        try { com.vutocorp.livevillage.Mascotas.guardar(); } catch (Exception ignored) { }
        sender.sendMessage("Regla quitada: " + r.describe() + " -> " + r.mob + ".  Quedan " + reglas.size() + ".");
        return 1;
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
            .then(Commands.literal("log")
                .then(Commands.literal("on").executes(c -> giftLog(c, true)))
                .then(Commands.literal("off").executes(c -> giftLog(c, false)))
                .then(Commands.literal("show").executes(this::giftVistos)))
            .then(Commands.literal("simulate")
                .then(argPueblo()
                    .then(Commands.argument("donador", StringArgumentType.word())
                        .then(Commands.argument("giftId", LongArgumentType.longArg(0))
                            .then(Commands.argument("monedas", IntegerArgumentType.integer(0))
                                .executes(this::giftSimulate))))))
            .then(Commands.literal("set")
                .then(Commands.literal("id")
                    .then(Commands.argument("giftId", LongArgumentType.longArg(1))
                        .then(Commands.argument("modelo", StringArgumentType.word())
                            .suggests((c, b) -> sugerir(b, Skins.modelIds()))
                            .executes(c -> giftSet(c, LongArgumentType.getLong(c, "giftId"), null, 0, 0, 0,
                                StringArgumentType.getString(c, "modelo"))))))
                .then(Commands.literal("monedas")
                    .then(Commands.argument("cantidad", IntegerArgumentType.integer(1))
                        .then(Commands.argument("modelo", StringArgumentType.word())
                            .suggests((c, b) -> sugerir(b, Skins.modelIds()))
                            .executes(c -> giftSet(c, 0, null, IntegerArgumentType.getInteger(c, "cantidad"), 0, 0,
                                StringArgumentType.getString(c, "modelo"))))))
                .then(Commands.literal("tramo")
                    .then(Commands.argument("min", IntegerArgumentType.integer(1))
                        .then(Commands.argument("max", IntegerArgumentType.integer(1))
                            .then(Commands.argument("modelo", StringArgumentType.word())
                                .suggests((c, b) -> sugerir(b, Skins.modelIds()))
                                .executes(c -> giftSet(c, 0, null, 0,
                                    IntegerArgumentType.getInteger(c, "min"),
                                    IntegerArgumentType.getInteger(c, "max"),
                                    StringArgumentType.getString(c, "modelo")))))))
                .then(Commands.literal("nombre")
                    .then(Commands.argument("modelo", StringArgumentType.word())
                        .suggests((c, b) -> sugerir(b, Skins.modelIds()))
                        .then(Commands.argument("regalo", StringArgumentType.greedyString())
                            .executes(c -> giftSet(c, 0, StringArgumentType.getString(c, "regalo"), 0, 0, 0,
                                StringArgumentType.getString(c, "modelo")))))))
            .then(Commands.literal("remove")
                .then(Commands.argument("indice", IntegerArgumentType.integer(1))
                    .executes(this::giftRemove)));
    }

    private int giftSet(CommandContext<CommandSourceStack> c, long giftId, String nombre,
                        int monedas, int min, int max, String modelo) {
        CommandSender sender = c.getSource().getSender();
        if (Skins.model(modelo) == null) {
            sender.sendMessage("No existe el modelo '" + modelo + "'. Mira /lv model list.");
            return 0;
        }
        var r = new com.vutocorp.livevillage.Regalos.Regla();
        r.giftId = giftId; r.nombre = nombre; r.monedas = monedas;
        r.monedasMin = min; r.monedasMax = max; r.modelo = modelo;
        com.vutocorp.livevillage.Regalos.poner(r);
        try { com.vutocorp.livevillage.Regalos.guardar(); }
        catch (Exception e) { sender.sendMessage("Regla aplicada, pero no pude guardarla en el fichero: " + e.getMessage()); }
        sender.sendMessage("Regla: " + r.describe() + "  ->  " + modelo);
        return 1;
    }

    private int giftRemove(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        int indice = IntegerArgumentType.getInteger(c, "indice");
        var reglas = com.vutocorp.livevillage.Regalos.reglas();
        if (indice < 1 || indice > reglas.size()) {
            sender.sendMessage("Numero fuera de rango (1.." + reglas.size() + "). Mira /lv gift list.");
            return 0;
        }
        var r = reglas.remove(indice - 1);
        try { com.vutocorp.livevillage.Regalos.guardar(); } catch (Exception ignored) { }
        sender.sendMessage("Regla quitada: " + r.describe() + " -> " + r.modelo + ".  Quedan " + reglas.size() + ".");
        return 1;
    }

    private int giftLog(CommandContext<CommandSourceStack> c, boolean on) {
        com.vutocorp.livevillage.Regalos.registrando = on;
        c.getSource().getSender().sendMessage(on
            ? "Registro de regalos ON. Envia el regalo en tu directo y luego /lv gift log show "
              + "para ver su giftId real. Es la forma fiable de saberlo: el NOMBRE depende del "
              + "idioma de la conexion, el id no."
            : "Registro de regalos OFF.");
        return 1;
    }

    private int giftVistos(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        var vistos = com.vutocorp.livevillage.Regalos.vistos();
        if (vistos.isEmpty()) {
            sender.sendMessage("Todavia no ha entrado ningun regalo. Activa /lv gift log on y espera uno.");
            return 1;
        }
        sender.sendMessage("Regalos vistos (id -> nombre y monedas):");
        for (var e : vistos.entrySet()) sender.sendMessage("  " + e.getKey() + "  ->  " + e.getValue());
        return 1;
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
