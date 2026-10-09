package com.killercats.servercore.economy.payday;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.data.PlayerData;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Perms;
import com.killercats.servercore.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Rank-based salary for active playtime. Every minute a non-AFK player gains one active minute;
 * after the configured interval they are paid according to their highest payday rank.
 */
public final class PaydayManager {

    private final ServerCore plugin;
    private BukkitTask task;

    public PaydayManager(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (plugin.getConfig().getBoolean("payday.enabled", true)) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1200L, 1200L);
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }

    public int interval() {
        return Math.max(1, plugin.getConfig().getInt("payday.interval-minutes", 60));
    }

    public double salary(Player player) {
        double best = 0;
        ConfigurationSection ranks = plugin.getConfig().getConfigurationSection("payday.ranks");
        if (ranks == null) {
            return 0;
        }
        for (String rank : ranks.getKeys(false)) {
            if (Perms.explicit(player, "servercore.payday.rank." + rank)) {
                best = Math.max(best, ranks.getDouble(rank));
            }
        }
        return best;
    }

    private void tick() {
        int interval = interval();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("servercore.payday") || plugin.activity().isAfk(player)) {
                continue;
            }
            PlayerData data = plugin.players().get(player);
            int minutes = data.getPaydayMinutes() + 1;
            if (minutes < interval) {
                data.setPaydayMinutes(minutes);
                continue;
            }
            data.setPaydayMinutes(0);
            double amount = Money.round(salary(player));
            if (amount <= 0 || !plugin.economy().deposit(player, amount)) {
                continue;
            }
            plugin.transactions().log(TransactionType.PAYDAY, player.getUniqueId(), null, amount, interval + " active minutes");
            plugin.messages().send(player, "payday.paid", "amount", Money.format(amount), "minutes", interval);
            Sounds.play(player, plugin.getConfig().getString("sounds.money-received", "ORB_PICKUP"));
        }
    }
}
