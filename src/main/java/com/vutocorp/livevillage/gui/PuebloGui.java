package com.vutocorp.livevillage.gui;

import com.vutocorp.livevillage.House;
import com.vutocorp.livevillage.Perms;
import com.vutocorp.livevillage.Village;
import com.vutocorp.livevillage.VillageData;
import com.vutocorp.livevillage.VillageEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI de inventario: reemplaza PantallaLv.java del mod (una pantalla de CLIENTE, con
 * su propio render()). Aqui no hay cliente que abrir: es un cofre normal que Bukkit ya
 * sabe dibujar en cualquier version, asi que no hace falta nada de GuiGraphicsExtractor
 * ni de las pantallas custom que el mod necesitaba portar a mano en cada version.
 *
 * Dos niveles, con barra de navegacion abajo:
 *   Lista de pueblos -> click en un pueblo -> lista de sus casas.
 * El click en una casa teletransporta si el jugador puede gestionar el pueblo (Perms);
 * si no, solo muestra la info sin moverlo.
 *
 * ---- Por que hay paginacion ----
 * Un inventario de Bukkit tiene 54 slots como MAXIMO y no crece: es un limite del
 * protocolo, no una decision. Hasta 0.7.0 esto no se tenia en cuenta y las casas que no
 * cabian simplemente no se dibujaban, sin ningun aviso. Peor todavia: el tamaño se
 * calculaba sin contar la fila que ocupa la barra, asi que a partir de DIEZ casas ya se
 * perdian, y un pueblo de donaciones pasa de diez casas en un rato. Ahora nada se queda
 * fuera: lo que no cabe pasa a la pagina siguiente.
 */
public final class PuebloGui implements Listener {

    private static final Component TITULO_PUEBLOS = Component.text("LiveVillage - Pueblos", NamedTextColor.DARK_AQUA);

    /** Alto fijo: 6 filas. Las 5 primeras son contenido, la ultima la barra. */
    private static final int FILAS = 6;
    private static final int SIZE = FILAS * 9;          // 54, el maximo de Bukkit
    private static final int POR_PAGINA = SIZE - 9;     // 45
    private static final int SLOT_ANTERIOR = SIZE - 9;  // 45
    private static final int SLOT_VOLVER   = SIZE - 5;  // 49, centrado
    private static final int SLOT_SIGUIENTE = SIZE - 1; // 53

    // ---------------- apertura ----------------

    public static void abrirListaPueblos(Player p, VillageData datos) {
        abrirListaPueblos(p, datos, 0);
    }

    public static void abrirListaPueblos(Player p, VillageData datos, int pagina) {
        List<Village> todos = new ArrayList<>(datos.villages.values());
        pagina = encajar(pagina, todos.size());
        List<Village> pagina0 = trozo(todos, pagina);

        ListaHolder holder = new ListaHolder(datos, pagina0, pagina, paginas(todos.size()));
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, SIZE, TITULO_PUEBLOS);
        holder.inv = inv;
        for (int i = 0; i < pagina0.size(); i++) inv.setItem(i, itemPueblo(pagina0.get(i)));
        barra(inv, pagina, paginas(todos.size()), null);
        p.openInventory(inv);
    }

    public static void abrirPueblo(Player p, VillageData datos, String nombrePueblo) {
        abrirPueblo(p, datos, nombrePueblo, 0);
    }

    public static void abrirPueblo(Player p, VillageData datos, String nombrePueblo, int pagina) {
        Village v = datos.byName(nombrePueblo);
        if (v == null) { p.sendMessage("Ese pueblo ya no existe."); return; }
        List<House> todas = v.houses;
        pagina = encajar(pagina, todas.size());
        List<House> pagina0 = trozo(todas, pagina);

        VillaHolder holder = new VillaHolder(datos, v.name, pagina0, pagina, paginas(todas.size()));
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, SIZE,
            Component.text("Pueblo: " + v.name, NamedTextColor.DARK_AQUA));
        holder.inv = inv;
        for (int i = 0; i < pagina0.size(); i++) inv.setItem(i, itemCasa(pagina0.get(i)));
        barra(inv, pagina, paginas(todas.size()), "< Volver a pueblos");
        p.openInventory(inv);
    }

    // ---------------- paginacion ----------------

    private static int paginas(int total) { return Math.max(1, (total + POR_PAGINA - 1) / POR_PAGINA); }

    private static int encajar(int pagina, int total) {
        return Math.max(0, Math.min(pagina, paginas(total) - 1));
    }

    private static <T> List<T> trozo(List<T> todos, int pagina) {
        int desde = pagina * POR_PAGINA;
        return new ArrayList<>(todos.subList(Math.min(desde, todos.size()),
                                             Math.min(desde + POR_PAGINA, todos.size())));
    }

    /** Ultima fila: anterior / volver / siguiente. Los que no aplican se dejan vacios. */
    private static void barra(Inventory inv, int pagina, int paginas, String textoVolver) {
        if (pagina > 0) {
            inv.setItem(SLOT_ANTERIOR, item(Material.ARROW, "< Pagina " + pagina,
                NamedTextColor.YELLOW));
        }
        if (textoVolver != null) {
            inv.setItem(SLOT_VOLVER, item(Material.BARRIER, textoVolver, NamedTextColor.YELLOW));
        }
        if (pagina < paginas - 1) {
            inv.setItem(SLOT_SIGUIENTE, item(Material.ARROW, "Pagina " + (pagina + 2) + " >",
                NamedTextColor.YELLOW));
        }
    }

    // ---------------- items ----------------

    private static ItemStack item(Material mat, String texto, NamedTextColor color) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(texto, color).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack itemPueblo(Village v) {
        ItemStack it = new ItemStack(v.open ? Material.EMERALD : Material.REDSTONE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(v.name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(linea("Dueño", v.ownerName.isEmpty() ? "(admin)" : v.ownerName));
        lore.add(linea("Estado", v.open ? "abierto" : "cerrado"));
        lore.add(linea("Casas", String.valueOf(v.houses.size())));
        lore.add(Component.text("Click para ver sus casas", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack itemCasa(House h) {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("#" + h.num + " " + h.name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(linea("Modelo", h.modelId == null ? "provisional" : h.modelId));
        lore.add(linea("Oficio", h.profession == null ? "-" : h.profession));
        lore.add(linea("Posicion", h.x + ", " + h.y + ", " + h.z));
        lore.add(linea("Mascotas", String.valueOf(h.mobs.size())));
        lore.add(Component.text("Click para teletransportarte", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private static Component linea(String etiqueta, String valor) {
        return Component.text(etiqueta + ": ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            .append(Component.text(valor, NamedTextColor.WHITE));
    }

    // ---------------- holders (llevan el contexto: que lista se dibujo y en que orden) ----------------

    private static final class ListaHolder implements InventoryHolder {
        final VillageData datos;
        final List<Village> pueblos;     // SOLO los de esta pagina: el slot indexa aqui
        final int pagina, paginas;
        Inventory inv;
        ListaHolder(VillageData d, List<Village> p, int pag, int pags) {
            datos = d; pueblos = p; pagina = pag; paginas = pags;
        }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final class VillaHolder implements InventoryHolder {
        final VillageData datos;
        final String pueblo;
        final List<House> casas;         // SOLO las de esta pagina
        final int pagina, paginas;
        Inventory inv;
        VillaHolder(VillageData d, String pu, List<House> c, int pag, int pags) {
            datos = d; pueblo = pu; casas = c; pagina = pag; paginas = pags;
        }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------- clicks ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof ListaHolder) && !(holder instanceof VillaHolder)) return;
        e.setCancelled(true);                       // es un menu: nada se saca ni se mueve
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getSlot();

        if (holder instanceof ListaHolder lh) {
            if (slot == SLOT_ANTERIOR && lh.pagina > 0) {
                abrirListaPueblos(p, lh.datos, lh.pagina - 1); return;
            }
            if (slot == SLOT_SIGUIENTE && lh.pagina < lh.paginas - 1) {
                abrirListaPueblos(p, lh.datos, lh.pagina + 1); return;
            }
            if (slot < 0 || slot >= lh.pueblos.size()) return;
            abrirPueblo(p, lh.datos, lh.pueblos.get(slot).name);
            return;
        }

        VillaHolder vh = (VillaHolder) holder;
        if (slot == SLOT_VOLVER) { abrirListaPueblos(p, vh.datos); return; }
        if (slot == SLOT_ANTERIOR && vh.pagina > 0) {
            abrirPueblo(p, vh.datos, vh.pueblo, vh.pagina - 1); return;
        }
        if (slot == SLOT_SIGUIENTE && vh.pagina < vh.paginas - 1) {
            abrirPueblo(p, vh.datos, vh.pueblo, vh.pagina + 1); return;
        }
        if (slot < 0 || slot >= vh.casas.size()) return;
        House h = vh.casas.get(slot);
        Village v = vh.datos.byName(vh.pueblo);
        if (v == null) return;

        if (!Perms.puedeGestionar(p, v)) {
            p.sendMessage("La casa #" + h.num + " es de '" + h.name + "'. Solo el dueño del pueblo o un admin puede teletransportarse.");
            return;
        }
        p.closeInventory();
        p.teleport(new Location(VillageEngine.worldOf(v), h.x + 0.5, h.floorY(), h.z + 0.5));
    }
}
