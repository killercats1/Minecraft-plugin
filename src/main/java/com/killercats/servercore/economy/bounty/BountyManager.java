package com.killercats.servercore.economy.bounty;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Player-funded bounties paid out to whoever kills the target, with anti-abuse checks. */
public final class BountyManager implements Listener {

    private final ServerCore plugin;
    private final Map<UUID, Bounty> bounties = new ConcurrentHashMap<UUID, Bounty>();
    private final Map<UUID, Long> cooldowns = new HashMap<UUID, Long>();
    private final String upsertSql;

    public BountyManager(ServerCore plugin) {
        this.plugin = plugin;
        this.upsertSql = plugin.database().upsert("sc_bounties", "target", "target_name", "amount", "contributors", "updated");
        plugin.database().query("SELECT * FROM sc_bounties", rs -> {
            List<Bounty> list = new ArrayList<Bounty>();
            while (rs.next()) {
                Bounty bounty = new Bounty(UUID.fromString(rs.getString("target")), rs.getString("target_name"));
                bounty.deserializeContributions(rs.getString("contributors"));
                list.add(bounty);
            }
            return list;
        }).thenAccept(list -> plugin.sync(() -> {
            for (Bounty bounty : list) {
                bounties.put(bounty.target(), bounty);
            }
        }));
    }

    public Bounty get(UUID target) {
        return bounties.get(target);
    }

    public double amount(UUID target) {
        Bounty bounty = bounties.get(target);
        return bounty == null ? 0 : bounty.total();
    }

    public List<Bounty> sorted() {
        List<Bounty> list = new ArrayList<Bounty>(bounties.values());
        list.sort((a, b) -> Double.compare(b.total(), a.total()));
        return list;
    }

    private void save(Bounty bounty) {
        plugin.database().update(upsertSql, bounty.target().toString(), bounty.targetName(), bounty.total(),
                bounty.serializeContributions(), System.currentTimeMillis());
    }

    private void delete(UUID target) {
        bounties.remove(target);
        plugin.database().update("DELETE FROM sc_bounties WHERE target = ?", target.toString());
    }

    public void place(Player player, Player target, double amount) {
        if (target.equals(player)) {
            plugin.messages().send(player, "bounty.self");
            return;
        }
        if (target.hasPermission("servercore.bounty.exempt")) {
            plugin.messages().send(player, "bounty.exempt", "player", target.getName());
            return;
        }
        double min = plugin.getConfig().getDouble("bounty.min-amount", 1000);
        if (amount < min) {
            plugin.messages().send(player, "bounty.below-minimum", "min", Money.format(min));
            return;
        }
        long cooldown = plugin.getConfig().getLong("bounty.cooldown-seconds", 60) * 1000L;
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < cooldown) {
            plugin.messages().send(player, "general.cooldown", "time", ((cooldown - (System.currentTimeMillis() - last)) / 1000 + 1) + "s");
            return;
        }
        double tax = Money.round(amount * plugin.getConfig().getDouble("bounty.tax-percent", 10) / 100D);
        if (!plugin.economy().withdraw(player, amount)) {
            plugin.messages().send(player, "general.insufficient-funds", "amount", Money.format(amount));
            return;
        }
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        Bounty bounty = bounties.get(target.getUniqueId());
        if (bounty == null) {
            bounty = new Bounty(target.getUniqueId(), target.getName());
            bounties.put(target.getUniqueId(), bounty);
        }
        bounty.setTargetName(target.getName());
        bounty.add(player.getUniqueId(), amount - tax);
        save(bounty);
        plugin.transactions().log(TransactionType.BOUNTY_PLACE, player.getUniqueId(), target.getUniqueId(), -amount,
                "On " + target.getName() + (tax > 0 ? " (tax " + Money.format(tax) + ")" : ""));
        broadcast("bounty.placed-broadcast", "player", player.getName(), "target", target.getName(),
                "amount", Money.format(amount - tax), "total", Money.format(bounty.total()));
    }

    /** Admin removal; contributions are refunded. */
    public boolean remove(UUID target) {
        Bounty bounty = bounties.get(target);
        if (bounty == null) {
            return false;
        }
        for (Map.Entry<UUID, Double> entry : bounty.contributions().entrySet()) {
            plugin.economy().deposit(Bukkit.getOfflinePlayer(entry.getKey()), entry.getValue());
            plugin.transactions().log(TransactionType.BOUNTY_REFUND, entry.getKey(), target, entry.getValue(), "Bounty on " + bounty.targetName() + " removed");
        }
        delete(target);
        return true;
    }

    public Bounty findByName(String name) {
        for (Bounty bounty : bounties.values()) {
            if (bounty.targetName() != null && bounty.targetName().equalsIgnoreCase(name)) {
                return bounty;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        Bounty bounty = bounties.get(victim.getUniqueId());
        if (bounty == null || killer == null || killer.equals(victim)) {
            return;
        }
        if (!killer.hasPermission("servercore.bounty.claim")) {
            return;
        }
        if (plugin.getConfig().getBoolean("bounty.block-contributor-claim", true) && bounty.contributions().containsKey(killer.getUniqueId())) {
            plugin.messages().send(killer, "bounty.contributor-claim");
            return;
        }
        if (plugin.getConfig().getBoolean("bounty.block-same-ip", true) && killer.getAddress() != null && victim.getAddress() != null
                && killer.getAddress().getAddress().equals(victim.getAddress().getAddress())) {
            plugin.messages().send(killer, "bounty.same-ip");
            return;
        }
        double total = bounty.total();
        delete(victim.getUniqueId());
        if (!plugin.economy().deposit(killer, total)) {
            plugin.getLogger().severe("Could not pay bounty of " + total + " to " + killer.getName());
            return;
        }
        plugin.transactions().log(TransactionType.BOUNTY_CLAIM, killer.getUniqueId(), victim.getUniqueId(), total, "Killed " + victim.getName());
        broadcast("bounty.claimed-broadcast", "killer", killer.getName(), "target", victim.getName(), "amount", Money.format(total));
        Sounds.play(killer, plugin.getConfig().getString("sounds.success", "LEVEL_UP"));
    }

    private void broadcast(String key, Object... placeholders) {
        String message = plugin.messages().prefix() + plugin.messages().get(key, placeholders);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(message);
        }
    }
}
