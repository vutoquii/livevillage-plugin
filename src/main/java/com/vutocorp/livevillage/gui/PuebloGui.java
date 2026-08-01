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
 * Dos niveles, con "volver" en el primer slot de la segunda fila cuando aplica:
 *   Lista de pueblos -> click en un pueblo -> lista de sus casas.
 * El click en una casa teletransporta si el jugador puede gestionar el pueblo (Perms);
 * si no, solo muestra la info sin moverlo.
 */
public final class PuebloGui implements Listener {

    private static final Component TITULO_PUEBLOS = Component.text("LiveVillage - Pueblos", NamedTextColor.DARK_AQUA);

    // ---------------- apertura ----------------

    public static void abrirListaPueblos(Player p, VillageData datos) {
        List<Village> lista = new ArrayList<>(datos.villages.values());
        int size = Math.max(9, ((lista.size() + 8) / 9) * 9);
        ListaHolder holder = new ListaHolder(datos, lista);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, size, TITULO_PUEBLOS);
        holder.inv = inv;
        for (Village v : lista) inv.addItem(itemPueblo(v));
        p.openInventory(inv);
    }

    public static void abrirPueblo(Player p, VillageData datos, String nombrePueblo) {
        Village v = datos.byName(nombrePueblo);
        if (v == null) { p.sendMessage("Ese pueblo ya no existe."); return; }
        List<House> lista = v.houses;
        int size = Math.max(18, (((lista.size() + 1) + 8) / 9) * 9);
        VillaHolder holder = new VillaHolder(datos, v.name, lista);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, size,
            Component.text("Pueblo: " + v.name, NamedTextColor.DARK_AQUA));
        holder.inv = inv;
        inv.setItem(0, itemVolver());
        int slot = 9;
        for (House h : lista) { if (slot >= size) break; inv.setItem(slot++, itemCasa(h)); }
        p.openInventory(inv);
    }

    // ---------------- items ----------------

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

    private static ItemStack itemVolver() {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("< Volver a pueblos", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
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
        final List<Village> pueblos;
        Inventory inv;
        ListaHolder(VillageData d, List<Village> p) { datos = d; pueblos = p; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final class VillaHolder implements InventoryHolder {
        final VillageData datos;
        final String pueblo;
        final List<House> casas;
        Inventory inv;
        VillaHolder(VillageData d, String pu, List<House> c) { datos = d; pueblo = pu; casas = c; }
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
            if (slot < 0 || slot >= lh.pueblos.size()) return;
            abrirPueblo(p, lh.datos, lh.pueblos.get(slot).name);
            return;
        }

        VillaHolder vh = (VillaHolder) holder;
        if (slot == 0) { abrirListaPueblos(p, vh.datos); return; }
        int idx = slot - 9;
        if (idx < 0 || idx >= vh.casas.size()) return;
        House h = vh.casas.get(idx);
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
