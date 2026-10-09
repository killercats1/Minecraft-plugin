package com.killercats.servercore.features;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Rotating automatic broadcasts. */
public final class Announcer {

    private final ServerCore plugin;
    private BukkitTask task;
    private int index;

    public Announcer(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!plugin.getConfig().getBoolean("announcer.enabled", true)) {
            return;
        }
        long ticks = Math.max(10, plugin.getConfig().getLong("announcer.interval-seconds", 300)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::announce, ticks, ticks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }

    private void announce() {
        List<String> messages = plugin.getConfig().getStringList("announcer.messages");
        if (messages.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        String message;
        if (plugin.getConfig().getBoolean("announcer.random", false)) {
            message = messages.get(ThreadLocalRandom.current().nextInt(messages.size()));
        } else {
            message = messages.get(index++ % messages.size());
        }
        String prefix = plugin.getConfig().getString("announcer.prefix", "");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("servercore.announcer.receive")) {
                continue;
            }
            for (String line : message.split("\\\\n|\n")) {
                player.sendMessage(Text.color(plugin.placeholders().apply(player, prefix + line.replace("{player}", player.getName()))));
            }
        }
    }
}
