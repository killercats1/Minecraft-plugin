package com.killercats.servercore.maintenance;

import com.killercats.servercore.ServerCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.List;

public final class MaintenanceListener implements Listener {

    private final ServerCore plugin;

    public MaintenanceListener(ServerCore plugin) {
        this.plugin = plugin;
    }

    private MaintenanceManager manager() {
        return plugin.maintenance();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!manager().isEnabled() || event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }
        Player player = event.getPlayer();
        if (manager().canJoin(player)) {
            return;
        }
        event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, manager().kickMessage());
        if (manager().notifyStaff()) {
            String note = plugin.messages().prefix() + plugin.messages().get("maintenance.join-attempt", "player", player.getName());
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("servercore.maintenance.notify")) {
                    staff.sendMessage(note);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (manager().isEnabled()) {
            plugin.messages().send(event.getPlayer(), "maintenance.join-reminder", "reason", manager().reason(),
                    "remaining", manager().remainingText());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(ServerListPingEvent event) {
        if (!manager().isEnabled()) {
            return;
        }
        List<String> motd = manager().motd();
        if (!motd.isEmpty()) {
            event.setMotd(motd.get(0) + "\n" + motd.get(1));
        }
        int max = plugin.getConfig().getInt("maintenance.max-players-display", -1);
        if (max >= 0) {
            event.setMaxPlayers(max);
        }
    }
}
