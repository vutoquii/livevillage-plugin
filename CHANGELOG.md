# Changelog

## 0.1.0 (sin publicar)

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
