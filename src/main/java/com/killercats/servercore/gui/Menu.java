package com.killercats.servercore.gui;

import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for chest GUIs. Menus are identified through their InventoryHolder, which is
 * reliable on 1.8 (titles are not). By default every click into the menu is cancelled.
 */
public abstract class Menu implements InventoryHolder {

    public interface ClickAction {
        void click(Player player, InventoryClickEvent event);
    }

    protected final Inventory inventory;
    private final Map<Integer, ClickAction> actions = new HashMap<Integer, ClickAction>();

    protected Menu(String title, int rows) {
        // 1.8 throws if an inventory title is longer than 32 characters.
        this.inventory = Bukkit.createInventory(this, rows * 9, Text.trim(Text.color(title), 32));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected void set(int slot, ItemStack item, ClickAction action) {
        inventory.setItem(slot, item);
        if (action == null) {
            actions.remove(slot);
        } else {
            actions.put(slot, action);
        }
    }

    protected void clear() {
        inventory.clear();
        actions.clear();
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    /** Called for every click while this menu is open (top or bottom inventory). */
    public void onClick(Player player, InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inventory.getSize()) {
            return;
        }
        ClickAction action = actions.get(raw);
        if (action != null) {
            action.click(player, event);
        }
    }

    public void onDrag(Player player, InventoryDragEvent event) {
        for (int slot : event.getRawSlots()) {
            if (slot < inventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public void onClose(Player player, InventoryCloseEvent event) {
    }
}
