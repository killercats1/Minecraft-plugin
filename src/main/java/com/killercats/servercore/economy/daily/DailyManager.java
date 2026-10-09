package com.killercats.servercore.economy.daily;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.data.PlayerData;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Perms;
import com.killercats.servercore.util.Sounds;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Daily login reward with a streak bonus and milestone rewards. */
public final class DailyManager implements Listener {

    private static final long DAY = 24L * 60 * 60 * 1000;

    private final ServerCore plugin;

    public DailyManager(ServerCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection config() {
        return plugin.getConfig().getConfigurationSection("daily");
    }

    public long cooldown() {
        return (long) (config().getDouble("cooldown-hours", 24) * 60 * 60 * 1000);
    }

    public long remaining(PlayerData data) {
        return Math.max(0, data.getLastDaily() + cooldown() - System.currentTimeMillis());
    }

    public void claim(Player player) {
        PlayerData data = plugin.players().get(player);
        long remaining = remaining(data);
        if (remaining > 0) {
            plugin.messages().send(player, "daily.wait", "time", TimeUtil.formatDuration(remaining));
            return;
        }
        long now = System.currentTimeMillis();
        long resetAfter = (long) (config().getDouble("streak-reset-hours", 48) * 60 * 60 * 1000);
        int streak = data.getLastDaily() > 0 && now - data.getLastDaily() <= resetAfter ? data.getDailyStreak() + 1 : 1;
        int maxStreak = config().getInt("max-streak", 30);
        int effective = Math.min(streak, maxStreak);

        double reward = config().getDouble("base-reward", 500) + (effective - 1) * config().getDouble("streak-bonus-per-day", 100);
        double rankMultiplier = 1;
        ConfigurationSection multipliers = config().getConfigurationSection("rank-multipliers");
        if (multipliers != null) {
            for (String rank : multipliers.getKeys(false)) {
                if (Perms.explicit(player, "servercore.daily.rank." + rank)) {
                    rankMultiplier = Math.max(rankMultiplier, multipliers.getDouble(rank));
                }
            }
        }
        reward *= rankMultiplier;
        double milestone = config().getDouble("milestones." + streak, 0);
        reward = Money.round(reward + milestone);

        if (!plugin.economy().deposit(player, reward)) {
            plugin.messages().send(player, "general.transaction-failed");
            return;
        }
        data.setLastDaily(now);
        data.setDailyStreak(streak);
        plugin.transactions().log(TransactionType.DAILY, player.getUniqueId(), null, reward, "Streak day " + streak);
        plugin.messages().send(player, "daily.claimed", "amount", Money.format(reward), "streak", streak);
        if (milestone > 0) {
            plugin.messages().send(player, "daily.milestone", "streak", streak, "amount", Money.format(milestone));
        }
        Sounds.play(player, plugin.getConfig().getString("sounds.success", "LEVEL_UP"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("servercore.daily") || !config().getBoolean("remind-on-join", true)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && remaining(plugin.players().get(player)) <= 0) {
                plugin.messages().send(player, "daily.available");
            }
        }, 60L);
    }
}
