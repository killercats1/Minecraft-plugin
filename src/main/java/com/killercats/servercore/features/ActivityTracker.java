package com.killercats.servercore.features;

import com.killercats.servercore.ServerCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a player is AFK, so passive income (interest, payday) can't be farmed by idling.
 * Uses EssentialsX's AFK status when available and our own inactivity timer as a fallback.
 */
public final class ActivityTracker implements Listener {

    private final ServerCore plugin;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<UUID, Long>();

    public ActivityTracker(ServerCore plugin) {
        this.plugin = plugin;
    }

    public boolean isAfk(Player player) {
        if (plugin.essentials().isAfk(player)) {
            return true;
        }
        long timeout = plugin.getConfig().getLong("afk.inactivity-timeout-seconds", 300) * 1000L;
        if (timeout <= 0) {
            return false;
        }
        Long last = lastActivity.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last > timeout;
    }

    private void touch(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        // Only real movement between blocks counts; head rotation alone is how AFK machines cheat.
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()) {
            if (!event.getPlayer().isInsideVehicle()) {
                touch(event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncPlayerChatEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
    }
}
