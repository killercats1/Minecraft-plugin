package com.killercats.servercore.economy.sell;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.gui.Menu;
import com.killercats.servercore.util.Inventories;
import com.killercats.servercore.util.ItemBuilder;
import com.killercats.servercore.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Drop-in sell chest: players put items in the top 5 rows and press the sell button. */
public final class SellMenu extends Menu {

    private static final int INPUT_SLOTS = 45;
    private static final int SELL_SLOT = 49;
    private static final int CANCEL_SLOT = 45;

    private final ServerCore plugin;
    private final Player player;
    private boolean handled;

    public SellMenu(ServerCore plugin, Player player) {
        super(plugin.messages().get("sell.menu-title"), 6);
        this.plugin = plugin;
        this.player = player;
        for (int i = INPUT_SLOTS; i < 54; i++) {
            inventory.setItem(i, ItemBuilder.filler());
        }
        inventory.setItem(CANCEL_SLOT, new ItemBuilder(Material.BARRIER).name("&cCancel").lore("&7Return all items").build());
        refresh();
    }

    private List<ItemStack> contents() {
        List<ItemStack> items = new ArrayList<ItemStack>();
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item);
            }
        }
        return items;
    }

    private void refresh() {
        SellManager.Result quote = plugin.sell().calculate(player, contents(), true);
        double multiplier = plugin.sell().multiplier(player);
        inventory.setItem(SELL_SLOT, new ItemBuilder(Material.EMERALD_BLOCK).name("&a&lSell items").lore(
                "&7Sellable items: &f" + quote.units,
                "&7Unsellable stacks: &c" + quote.unsold.size(),
                "&7Your multiplier: &bx" + multiplier,
                "",
                "&7You receive: &a" + Money.format(quote.total),
                "",
                "&eClick to sell").build());
    }

    private void refreshLater() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!handled) {
                refresh();
            }
        });
    }

    @Override
    public void onClick(Player clicker, InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        if (raw >= INPUT_SLOTS && raw < inventory.getSize()) {
            event.setCancelled(true);
            if (raw == SELL_SLOT) {
                complete(true);
            } else if (raw == CANCEL_SLOT) {
                complete(false);
            }
            return;
        }
        refreshLater();
    }

    @Override
    public void onDrag(Player dragger, InventoryDragEvent event) {
        for (int slot : event.getRawSlots()) {
            if (slot >= INPUT_SLOTS && slot < inventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
        refreshLater();
    }

    @Override
    public void onClose(Player closer, InventoryCloseEvent event) {
        if (!handled) {
            complete(plugin.getConfig().getBoolean("sell.gui-sell-on-close", false));
        }
    }

    private void complete(boolean sell) {
        if (handled) {
            return;
        }
        handled = true;
        List<ItemStack> items = new ArrayList<ItemStack>();
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item);
            }
            inventory.setItem(i, null);
        }
        if (sell) {
            SellManager.Result result = plugin.sell().sell(player, items);
            for (ItemStack rest : result.unsold) {
                Inventories.giveOrDrop(player, rest);
            }
            if (result.units > 0) {
                plugin.messages().send(player, "sell.sold", "amount", result.units, "money", Money.format(result.total));
            }
            if (!result.unsold.isEmpty()) {
                plugin.messages().send(player, "sell.returned", "count", result.unsold.size());
            }
        } else {
            for (ItemStack item : items) {
                Inventories.giveOrDrop(player, item);
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() == this) {
                player.closeInventory();
            }
        });
    }
}
