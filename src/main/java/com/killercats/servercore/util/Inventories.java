package com.killercats.servercore.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class Inventories {

    private Inventories() {
    }

    /** Gives an item, dropping whatever does not fit at the player's feet. */
    public static void giveOrDrop(Player player, ItemStack item) {
        if (item == null) {
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    /** Whether the whole stack would fit into the player's main inventory. */
    public static boolean canFit(Player player, ItemStack item) {
        int remaining = item.getAmount();
        ItemStack[] contents = player.getInventory().getContents();
        for (ItemStack slot : contents) {
            if (slot == null || slot.getType() == org.bukkit.Material.AIR) {
                remaining -= item.getMaxStackSize();
            } else if (slot.isSimilar(item)) {
                remaining -= Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    public static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack slot : player.getInventory().getContents()) {
            if (slot == null || slot.getType() == org.bukkit.Material.AIR) {
                free++;
            }
        }
        return free;
    }
}
