package com.vutocorp.livevillage;

/**
 * Conexion al LIVE de TikTok. Port LITERAL de cliente/ConexionTikTok.java del mod: es
 * el archivo que confirmaba desde el analisis inicial de este proyecto que no tiene NI
 * UN import de Minecraft o NeoForge. Los unicos cambios de verdad:
 *
 *   - Vivia en el paquete cliente/ porque en el mod la conexion la abre el CLIENTE del
 *     streamer (el jugador manda el regalo al servidor por red). Aqui el plugin ES el
 *     servidor: no hay cliente que abrir, asi que esta clase se conecta directamente
 *     desde donde antes estaba ClienteLv.conectar(). Ver TikTokManager.
 *   - carpetaLib() usaba FMLPaths.CONFIGDIR (API de NeoForge); aqui recibe la carpeta
 *     del plugin por parametro, fijada una vez desde LiveVillagePlugin.onEnable().
 *   - LiveVillage.LOGGER -> LiveVillagePlugin.LOGGER.
 *
 * Todo lo demas —el aislamiento por reflexion, el ClassLoader propio, el porque de cada
 * uno de los trucos con proxies dinamicos— es exactamente el mismo razonamiento que en
 * el mod y no cambia al pasar a plugin: la libreria de TikTok sigue siendo la misma
 * ingenieria inversa fragil, tenga quien la llame forma de mod o de plugin.
 */
public final class ConexionTikTok {

    public interface Escucha {
        /** Un regalo. monedas ya viene multiplicado por las repeticiones de la racha. */
        void onRegalo(long giftId, String nombreRegalo, int monedas, int veces, String donador);
        void onAviso(String texto);
    }

    private final Escucha escucha;
    private Object cliente;          // io.github.jwdeveloper.tiktok.live.LiveClient
    private String usuario = "";
    private volatile boolean conectado = false;

    public ConexionTikTok(Escucha escucha) { this.escucha = escucha; }

    public boolean conectado() { return conectado; }
    public String usuario() { return usuario; }

    static final String CLASE_PRINCIPAL = "io.github.jwdeveloper.tiktok.TikTokLive";
    public static final String CARPETA = "livevillage-lib";

    private static java.nio.file.Path carpetaLibFija;
    private static ClassLoader cargador;
    private static String dondeSalio = "no encontrada";

    /** Fijada una vez desde LiveVillagePlugin.onEnable(): plugins/LiveVillage/livevillage-lib/. */
    public static void usarCarpeta(java.nio.file.Path carpetaPlugin) {
        carpetaLibFija = carpetaPlugin.resolve(CARPETA);
    }

    public static java.nio.file.Path carpetaLib() { return carpetaLibFija; }

    /**
     * Devuelve el ClassLoader que puede ver la libreria, o null. Se prueba en dos sitios:
     * primero el classpath normal (por si alguien la metio como plugin), y si no, la carpeta.
     */
    static synchronized ClassLoader cargador() {
        if (cargador != null) return cargador;
        try {
            Class.forName(CLASE_PRINCIPAL);
            cargador = ConexionTikTok.class.getClassLoader();
            dondeSalio = "classpath del servidor";
            return cargador;
        } catch (Throwable ignorado) { }

        java.nio.file.Path dir = carpetaLibFija;
        if (dir == null) { dondeSalio = "carpeta de la libreria no configurada"; return null; }
        try {
            if (!java.nio.file.Files.isDirectory(dir)) {
                java.nio.file.Files.createDirectories(dir);
                dondeSalio = "carpeta " + dir + " (vacia)";
                return null;
            }
            java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path f : st.toList())
                    if (f.toString().toLowerCase().endsWith(".jar")) urls.add(f.toUri().toURL());
            }
            if (urls.isEmpty()) { dondeSalio = "carpeta " + dir + " (sin .jar dentro)"; return null; }
            // Padre = el ClassLoader del plugin, para que la libreria vea a Java pero traiga
            // sus PROPIAS copias de protobuf y websocket sin pisar las de nadie.
            ClassLoader cl = new java.net.URLClassLoader(
                urls.toArray(new java.net.URL[0]), ConexionTikTok.class.getClassLoader());
            Class.forName(CLASE_PRINCIPAL, false, cl);
            cargador = cl;
            dondeSalio = urls.size() + " jar(s) en " + dir;
            return cargador;
        } catch (ClassNotFoundException e) {
            dondeSalio = "hay jars en " + dir + " pero ninguno trae " + CLASE_PRINCIPAL;
            return null;
        } catch (Throwable e) {
            dondeSalio = "error leyendo " + dir + ": " + resumen(e);
            return null;
        }
    }

    public static String dondeSalio() { cargador(); return dondeSalio; }

    public static boolean disponible() { return cargador() != null; }

    // ---------------- conexion ----------------

    public void conectar(String usuarioTikTok) {
        desconectar();
        this.usuario = usuarioTikTok;
        if (!disponible()) {
            escucha.onAviso("No encuentro la libreria de TikTok: " + dondeSalio());
            escucha.onAviso("Copia los jars de TikTokLiveJava a " + carpetaLib()
                + "  (o usa el puente de Python). Detalles: /lv tiktok diag");
            return;
        }
        // En su propio hilo: la conexion bloquea y no puede colgar el hilo principal del server.
        Thread t = new Thread(() -> {
            try {
                arrancar(usuarioTikTok);
            } catch (Throwable e) {
                conectado = false;
                LiveVillagePlugin.LOGGER.severe("[LiveVillage] Fallo conectando a TikTok: " + e);
                escucha.onAviso("No pude conectar con @" + usuarioTikTok + ": " + resumen(e));
            }
        }, "livevillage-live");
        t.setDaemon(true);
        t.start();
    }

    public void desconectar() {
        conectado = false;
        Object c = cliente;
        cliente = null;
        if (c == null) return;
        try {
            c.getClass().getMethod("disconnect").invoke(c);
        } catch (Throwable ignored) {
            // Si el nombre del metodo cambio, el hilo es daemon y muere con el servidor.
        }
    }

    // ---------------- reflexion sobre la libreria ----------------

    private void arrancar(String usuarioTikTok) throws Exception {
        ClassLoader cl = cargador();
        if (cl == null) throw new IllegalStateException("libreria no disponible");
        // El hilo debe tener este ClassLoader como contextual: la libreria carga sus propias
        // clases de protobuf/websocket por ese camino.
        Thread.currentThread().setContextClassLoader(cl);
        Class<?> tikTokLive = Class.forName(CLASE_PRINCIPAL, true, cl);
        Object builder = tikTokLive.getMethod("newClient", String.class).invoke(null, usuarioTikTok);

        configurarIdiomaIngles(builder);
        registrarEscuchaDeRegalos(builder);

        Object c = invocarPrimeroQueExista(builder,
            new String[]{"buildAndConnect", "buildAndConnectAsync", "connect"});
        if (c == null) {
            Object construido = invocarPrimeroQueExista(builder, new String[]{"build"});
            if (construido == null) throw new IllegalStateException(
                "no encuentro como construir el cliente (probe buildAndConnect/connect/build)");
            invocarPrimeroQueExista(construido, new String[]{"connect", "connectAsync"});
            c = construido;
        }
        cliente = c;
        conectado = true;
        escucha.onAviso("Conectado al LIVE de @" + usuarioTikTok);
    }

    /** Fuerza ingles para que los nombres de regalo lleguen SIEMPRE iguales. */
    private void configurarIdiomaIngles(Object builder) {
        try {
            for (java.lang.reflect.Method m : builder.getClass().getMethods()) {
                if (!m.getName().equals("configure") || m.getParameterCount() != 1) continue;
                Class<?> tipo = m.getParameterTypes()[0];
                if (!tipo.isInterface()) continue;
                Object lambda = java.lang.reflect.Proxy.newProxyInstance(
                    tipo.getClassLoader(), new Class<?>[]{tipo},
                    (proxy, metodo, args) -> {
                        Object obj = respuestaDeObject(proxy, metodo, args);
                        if (obj != null) return obj;
                        if (args != null && args.length == 1) {
                            Object settings = args[0];
                            llamarSiExiste(settings, "setClientLanguage", String.class, "en");
                            llamarSiExiste(settings, "setRetryOnConnectionFailure", boolean.class, true);
                            llamarSiExiste(settings, "setPrintToConsole", boolean.class, false);
                        }
                        return null;
                    });
                m.invoke(builder, lambda);
                return;
            }
        } catch (Throwable e) {
            LiveVillagePlugin.LOGGER.warning("[LiveVillage] No pude fijar el idioma en ingles; "
                + "los nombres de regalo pueden llegar traducidos. Usa reglas por giftId.");
        }
    }

    private void registrarEscuchaDeRegalos(Object builder) throws Exception {
        java.lang.reflect.Method onGift = null;
        for (java.lang.reflect.Method m : builder.getClass().getMethods()) {
            if (m.getName().equals("onGift") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInterface()) { onGift = m; break; }
        }
        if (onGift == null) throw new IllegalStateException("la libreria no expone onGift(...)");

        Class<?> tipo = onGift.getParameterTypes()[0];
        Object lambda = java.lang.reflect.Proxy.newProxyInstance(
            tipo.getClassLoader(), new Class<?>[]{tipo},
            (proxy, metodo, args) -> {
                Object obj = respuestaDeObject(proxy, metodo, args);
                if (obj != null) return obj;
                try {
                    Object evento = (args == null || args.length == 0) ? null : args[args.length - 1];
                    if (evento != null) procesarRegalo(evento);
                } catch (Throwable e) {
                    LiveVillagePlugin.LOGGER.severe("[LiveVillage] Error leyendo un regalo: " + e);
                }
                return null;
            });
        onGift.invoke(builder, lambda);
    }

    /**
     * Lee un TikTokGiftEvent. Firmas VERIFICADAS con javap sobre el jar real (mismo dato
     * que documenta el mod):
     *   TikTokGiftEvent.getGift() -> Gift ; .getUser() -> User ; .getCombo() -> int
     *   Gift.getId() -> int ; .getName() -> String ; .getDiamondCost() -> int
     *   User.getProfileName() -> String ; .getName() -> String
     */
    private void procesarRegalo(Object evento) {
        Object gift = leer(evento, "getGift");
        if (gift == null) return;
        long id = numero(leer(gift, "getId"), 0);
        String nombre = texto(leer(gift, "getName"), "");
        int coste = (int) numero(leer(gift, "getDiamondCost"), 0);

        int veces = (int) numero(leer(evento, "getCombo"), 1);
        if (veces < 1) veces = 1;

        Object user = leer(evento, "getUser");
        String donador = texto(primerNoNulo(user, "getProfileName", "getName"), "Anon");
        if (donador.isEmpty()) donador = "Anon";

        escucha.onRegalo(id, nombre, coste * veces, veces, donador);
    }

    /** Respuesta a los metodos heredados de Object en un proxy dinamico (hashCode/equals/
     *  toString). Sin esto la libreria mete el listener en un HashSet/HashMap, llama a
     *  hashCode(), recibe null del InvocationHandler e intenta desempaquetarlo a int:
     *  NullPointerException. Es el fallo clasico de los proxies dinamicos. */
    private static Object respuestaDeObject(Object proxy, java.lang.reflect.Method m, Object[] args) {
        if (m.getDeclaringClass() != Object.class) return null;
        switch (m.getName()) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals":   return args != null && args.length == 1 && proxy == args[0];
            case "toString": return "LiveVillage$" + m.getDeclaringClass().getSimpleName()
                                    + "@" + Integer.toHexString(System.identityHashCode(proxy));
            default:         return null;
        }
    }

    // ---------------- utilidades de reflexion ----------------

    private static Object leer(Object o, String metodo) {
        if (o == null) return null;
        try { return o.getClass().getMethod(metodo).invoke(o); }
        catch (Throwable t) { return null; }
    }

    private static Object primerNoNulo(Object o, String... metodos) {
        for (String m : metodos) {
            Object r = leer(o, m);
            if (r != null) return r;
        }
        return null;
    }

    private static Object invocarPrimeroQueExista(Object o, String[] metodos) {
        for (String m : metodos) {
            try { return o.getClass().getMethod(m).invoke(o); }
            catch (NoSuchMethodException ignored) { }
            catch (Throwable t) { return null; }
        }
        return null;
    }

    private static void llamarSiExiste(Object o, String metodo, Class<?> tipo, Object valor) {
        try { o.getClass().getMethod(metodo, tipo).invoke(o, valor); }
        catch (Throwable ignored) { }
    }

    private static long numero(Object o, long porDefecto) {
        return (o instanceof Number n) ? n.longValue() : porDefecto;
    }

    private static String texto(Object o, String porDefecto) {
        return o == null ? porDefecto : String.valueOf(o);
    }

    private static String resumen(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
