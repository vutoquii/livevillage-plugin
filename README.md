# LiveVillage (plugin)

Plugin de servidor Paper que construye un pueblo cuyas casas se colocan con
donaciones de un directo de TikTok. Es el mismo concepto que
[livevillage-mod](../livevillage-mod) pero para servidor en vez de mod de
cliente: ver `ROADMAP.md` en `../livevillage-plugin-spike` para el porque de
este proyecto y las decisiones de cada fase.

## Requisitos

- Servidor **Paper** (no Spigot ni Bukkit puro: usa `StructureManager`/`Palette`,
  que son API de Paper).
- Minecraft/Paper **26.2** o compatible con `api-version: 1.21.4` en `plugin.yml`.
- Java 25 para compilar y para correr el servidor.

## Diferencia importante frente al mod

El mod corre en el mundo de un solo jugador: el streamer abre Minecraft y el
cliente se conecta a TikTok, sin servidor de por medio. **Este plugin necesita
un servidor Paper corriendo.** A cambio, los espectadores pueden entrar al
pueblo con un cliente vanilla (nadie instala nada), y no hace falta portar el
plugin cada vez que cambia la version de Minecraft: la API de Paper es
estable entre versiones, a diferencia de las internas de NeoForge que rompen
en cada salto.

## Instalacion

1. Bajar `livevillage-plugin-<version>.jar` y copiarlo a `plugins/` del
   servidor Paper.
2. Arrancar el servidor una vez para que se generen las carpetas de config en
   `plugins/LiveVillage/`.
3. (Opcional) Copiar los `.jar` de TikTokLiveJava a
   `plugins/LiveVillage/livevillage-lib/` para conectar de verdad a un
   directo. Sin ellos, todo lo demas (`/lv house add`, `/lv gift simulate`)
   funciona igual: solo falta la conexion real a TikTok.

## Uso basico

```
/lv village create <nombre> [skin]      crea un pueblo en tu posicion (plaza + avenidas)
/lv house add <pueblo> <donador> [modelo]   coloca una casa a mano
/lv tiktok connect <usuario> <pueblo>   conecta un directo real
/lv gui                                  menu de inventario: pueblos -> casas
```

Ver el arbol completo en el codigo (`LvBrigadier.java`): `village`, `house`,
`model`, `mob`, `tiktok`, `gift`, `perm`.

## Compilar

```bash
./gradlew build
```

El jar queda en `build/libs/livevillage-plugin-<version>.jar`.

## Estado del port

Fases 1 a 5 del roadmap completas y probadas en servidor real (no solo
compiladas): esqueleto + persistencia, motor de construccion, aldeanos y
mascotas, TikTok y donaciones, comandos con Brigadier + GUI. Falta la Fase 6:
empaquetado/publicacion formal en Hangar.
