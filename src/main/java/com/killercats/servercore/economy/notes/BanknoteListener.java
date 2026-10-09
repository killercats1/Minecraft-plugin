package com.killercats.servercore.economy.notes;

import com.killercats.servercore.ServerCore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public final class BanknoteListener implements Listener {

    private final ServerCore plugin;

    public BanknoteListener(ServerCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (BanknoteManager.readId(event.getItem()) == null) {
            return;
        }
        event.setCancelled(true);
        if (!event.getPlayer().hasPermission("servercore.banknote.redeem")) {
            plugin.messages().send(event.getPlayer(), "general.no-permission");
            return;
        }
        plugin.banknotes().redeemHand(event.getPlayer());
    }
}
