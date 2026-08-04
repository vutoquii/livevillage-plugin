# Changelog

## 0.8.0

### Arreglado

- **`/lv gui` se tragaba las casas en silencio a partir de la decima.** El inventario
  reservaba una fila entera para la flecha "volver" pero no la sumaba al tamaño, asi que las
  casas que no cabian simplemente no se dibujaban, sin ningun aviso: 15 casas mostraban 9, 60
  mostraban 45. Un pueblo de donaciones pasa de diez casas en un rato, asi que en la practica
  casi nunca se veian las casas propias.

  Ademas un inventario tiene 54 huecos como maximo y no crece, asi que sin paginacion no
  habia forma de ver un pueblo grande entero. Ahora las dos listas (pueblos y casas) van
  paginadas, con barra de navegacion abajo. No se queda nada fuera.

## 0.7.0

Salta de 0.2.1 a 0.7.0 para ir a la par con el mod, que en esta version estrena soporte de
Fabric. **El plugin no cambia de comportamiento**: el codigo es el mismo que 0.2.1.

### Cambiado (interno: CI)

- El canal de Hangar y el tipo de version en Modrinth salen ahora del NOMBRE DEL TAG
  (`v0.7.0-beta` -> Beta) en vez de estar fijos a Release. Antes, publicar una beta obligaba
  a lanzar el workflow a mano desde Actions.

## 0.2.1

- Se publica tambien en Modrinth (proyecto compartido con el mod,
  [livevillages](https://modrinth.com/project/livevillages), loader `paper`),
  ademas de Hangar. Sin cambios de codigo.

## 0.2.0

Completa el arbol de comandos que 0.1.0 dejaba pendiente, y arregla dos cosas
encontradas probando contra un directo real:

- **`/lv gift set id|monedas|tramo|nombre`** y **`/lv gift remove`**: editar la
  tabla de regalo-a-casa por comando, sin tocar el JSON a mano. El backend
  (`Regalos.poner/guardar`) ya estaba portado; faltaba el comando.
- **`/lv mob list|remove`** y **`/lv mob set id|nombre|monedas|tramo`**: lo
  mismo para la tabla de regalo-a-mascota (`Mascotas`).
- **`/lv village ai on|off|status`** y **`/lv village forceload on|off|status`**:
  pausa de IA y fijado manual de chunks durante un directo largo. Trae
  `Chunks.java` (version reducida del homonimo del mod: solo el modo manual,
  sin el `Guard` automatico de construccion, que en Bukkit no hace falta -
  ver el comentario de la clase).
- **La pausa de IA no hacia nada**: `Villagers.tickAiPause()` estaba escrito
  desde 0.1.0 pero nada lo llamaba nunca. Ahora `LiveVillagePlugin.onEnable()`
  la programa cada `Cfg.AI_CHECK_INTERVAL` ticks, igual que el mod la llama
  desde su evento de tick de servidor.
- **`/lv tiktok connect` no avisaba de nada al chat**: `onAviso()` (conectado,
  error, reconectando) solo se logueaba en la consola del servidor. Ahora
  tambien le llega a quien ejecuto el connect.
- Quitada una mencion residual al puente de Python en `ConexionTikTok.java`
  que se coló al portar el archivo antes de que el mod le quitara la suya.

## 0.1.0

Primera version funcional del port a plugin Paper. Fases 1-5 del roadmap,
probadas en servidor real:

- Esqueleto del plugin, persistencia de pueblos en YAML (`Village`, `House`,
  `VillageData`), sin necesitar el `SavedData`/Codec del mod.
- Estructuras (`Structures`), motor de construccion (`VillageEngine`) y
  caminos con A* (`PathBuilder`) sobre la API de `StructureManager`/`Palette`
  de Bukkit: sin parsear NBT a mano, a diferencia del mod.
- Aldeanos y mascotas (`Villagers`, `Mascotas`) sobre la API estable de
  Bukkit: no hace falta el `Compat.java` que el mod necesita para las
  internas de NeoForge.
- Conexion a TikTok (`ConexionTikTok`) movida literal desde el mod: es la
  unica clase que ya era pura logica Java, sin cambios de fondo.
  `Donaciones` porta el ritmo/cooldown/tope de casas por minuto.
  `TikTokManager` reemplaza al protocolo cliente-servidor del mod
  (`DonacionPayload`/`ControlPayload`/`ClienteLv`), que aqui no hace falta:
  el plugin ES el servidor.
- Arbol de comandos `/lv` reescrito con Brigadier (autocompletado real desde
  los datos vivos) y GUI de inventario para navegar pueblos y casas,
  reemplazando la pantalla de cliente `PantallaLv` del mod.

### Encontrado y arreglado durante las pruebas en servidor real

- `/lv village create` no construia la plaza (solo registraba datos); ahora
  levanta plaza + avenidas en el momento, como el comando real del mod.
- Teletransportes a una casa (comando y GUI) usaban `house.y + 1`, que es la
  altura del TERRENO al sortear la parcela, no el suelo acabado: con
  `Skin.lift > 0` dejaban al jugador metido en el suelo. Centralizado en
  `House.floorY()`.

### Pendiente (Fase 6)

- Confirmar el slug del proyecto en Hangar una vez creada la cuenta/proyecto.
- Primera publicacion real (requiere `HANGAR_API_KEY` como secreto de CI).
