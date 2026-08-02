package com.vutocorp.livevillage;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Punto de entrada de TikTok en el plugin. Reemplaza a cliente/ClienteLv.java del mod.
 *
 * La diferencia de fondo con el mod, y la razon de que este fichero sea tan corto: en
 * el mod la conexion la abre el CLIENTE del streamer y el regalo cruza la red hasta el
 * servidor (DonacionPayload) porque cliente y servidor son procesos distintos, y el
 * servidor no se fia de lo que le manda un cliente que se puede modificar. Aqui el
 * plugin ES el servidor: no hay frontera de red que cruzar ni cliente ajeno del que
 * desconfiar, asi que ConexionTikTok.Escucha llama a Donaciones.porRegalo() DIRECTO, en
 * el mismo proceso. Todo el protocolo de paquetes (DonacionPayload, ControlPayload,
 * ClienteLv) desaparece sin reemplazo: no hace falta.
 *
 * Sigue habiendo UNA sola conexion activa a la vez, igual que en el mod (un streamer,
 * un directo). Si algun dia hace falta que el mismo servidor atienda varios directos en
 * paralelo, esto pasaria a un Map<pueblo, ConexionTikTok>; hoy no esta pedido y anadirlo
 * sin necesidad seria complejidad de sobra.
 */
public final class TikTokManager {

    private TikTokManager() {}

    private static ConexionTikTok conexion;
    private static String puebloDestino = "";
    private static VillageData datos;
    // Quien mando el /lv tiktok connect: onAviso llega en el hilo de la conexion, mucho
    // despues de que el comando ya devolvio, y es el unico sitio donde avisar de un
    // "conectado" o un fallo real (la libreria reintenta sola). Sin esto el aviso solo
    // se veia en la consola del servidor y nunca en el chat de quien lo pidio.
    private static CommandSender ultimoSender;

    public static void init(VillageData d) { datos = d; }

    public static String estadoTexto() {
        if (!ConexionTikTok.disponible())
            return "Libreria de TikTok NO disponible (" + ConexionTikTok.dondeSalio() + "). "
                 + "Mira /lv tiktok diag.";
        if (conexion == null || !conexion.conectado())
            return "Sin conectar. Usa /lv tiktok connect <usuario> <pueblo>.";
        return "Conectado a @" + conexion.usuario() + " -> pueblo '" + puebloDestino + "'.";
    }

    public static void diagnostico(CommandSender sender) {
        sender.sendMessage("--- diagnostico TikTok ---");
        sender.sendMessage("Carpeta esperada: " + ConexionTikTok.carpetaLib());
        sender.sendMessage("Resultado: " + ConexionTikTok.dondeSalio());
        if (ConexionTikTok.disponible()) {
            sender.sendMessage("Libreria OK. Ya puedes /lv tiktok connect <usuario> <pueblo>.");
        } else {
            sender.sendMessage("Como arreglarlo: copia los .jar de TikTokLiveJava a la carpeta de arriba"
                + " y ejecuta de nuevo /lv tiktok diag.");
        }
    }

    public static void conectar(CommandSender sender, String usuario, String pueblo) {
        if (datos.byName(pueblo) == null) {
            sender.sendMessage("No existe el pueblo '" + pueblo + "'.");
            return;
        }
        puebloDestino = pueblo;
        ultimoSender = sender;
        if (conexion == null) {
            conexion = new ConexionTikTok(new ConexionTikTok.Escucha() {
                @Override
                public void onRegalo(long giftId, String nombreRegalo, int monedas, int veces, String donador) {
                    // Se procesa en el hilo principal: todo lo que toca el mundo (bloques,
                    // entidades) tiene que correr ahi, y el callback de la libreria llega
                    // desde su propio hilo de red.
                    Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugin("LiveVillage"), () -> {
                            Village v = datos.byName(puebloDestino);
                            Donaciones.Resultado r = Donaciones.porRegalo(v, giftId, nombreRegalo, monedas, donador);
                            if (r.ok()) datos.guardar();
                            Bukkit.getLogger().info("[LiveVillage] " + donador + ": " + nombreRegalo
                                + " x" + veces + " (" + monedas + " monedas) -> " + r.detalle);
                        });
                }

                @Override
                public void onAviso(String texto) {
                    LiveVillagePlugin.LOGGER.info("[LiveVillage] " + texto);
                    CommandSender destino = ultimoSender;
                    if (destino == null) return;
                    // onAviso llega desde el hilo de la conexion (ver ConexionTikTok.conectar):
                    // cualquier cosa que toque la API de Bukkit tiene que volver al hilo principal.
                    Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugin("LiveVillage"),
                        () -> destino.sendMessage("[LiveVillage] " + texto));
                }
            });
        }
        sender.sendMessage("Conectando con @" + usuario + "...");
        conexion.conectar(usuario);
    }

    public static void desconectar(CommandSender sender) {
        if (conexion != null) conexion.desconectar();
        sender.sendMessage("Desconectado de TikTok.");
    }
}
