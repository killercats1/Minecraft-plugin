package com.killercats.servercore.economy.bank;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.data.PlayerData;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Sounds;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Personal bank accounts stored next to (not inside) the Essentials wallet. Money in the bank is
 * safe from /pay mistakes and earns interest depending on the account tier. Accounts have a
 * capacity per tier, which acts as an inflation brake.
 */
public final class BankManager {

    private final ServerCore plugin;
    private final List<BankTier> tiers = new ArrayList<BankTier>();
    private BukkitTask interestTask;

    public BankManager(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tiers.clear();
        List<Map<?, ?>> list = plugin.getConfig().getMapList("bank.tiers");
        int index = 0;
        for (Map<?, ?> map : list) {
            String name = String.valueOf(get(map, "name", "Tier " + (index + 1)));
            double cost = number(get(map, "cost", 0));
            double max = number(get(map, "max-balance", 100000));
            double interest = number(get(map, "interest-percent", 0));
            Object perm = map.get("permission");
            tiers.add(new BankTier(index++, Text.color(name), cost, max, interest, perm == null ? null : String.valueOf(perm)));
        }
        if (tiers.isEmpty()) {
            tiers.add(new BankTier(0, Text.color("&7Basic"), 0, 100000, 0.5, null));
        }
        if (interestTask != null) {
            interestTask.cancel();
            interestTask = null;
        }
        if (plugin.getConfig().getBoolean("bank.interest.enabled", true)) {
            long ticks = Math.max(1, plugin.getConfig().getLong("bank.interest.interval-minutes", 30)) * 60L * 20L;
            interestTask = Bukkit.getScheduler().runTaskTimer(plugin, this::payInterest, ticks, ticks);
        }
    }

    public void shutdown() {
        if (interestTask != null) {
            interestTask.cancel();
        }
    }

    private static Object get(Map<?, ?> map, String key, Object def) {
        Object value = map.get(key);
        return value == null ? def : value;
    }

    private static double number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public List<BankTier> tiers() {
        return Collections.unmodifiableList(tiers);
    }

    public BankTier tier(PlayerData data) {
        int index = Math.max(0, Math.min(tiers.size() - 1, data.getBankTier()));
        return tiers.get(index);
    }

    public BankTier nextTier(PlayerData data) {
        int next = tier(data).index() + 1;
        return next < tiers.size() ? tiers.get(next) : null;
    }

    public boolean deposit(Player player, double amount) {
        PlayerData data = plugin.players().get(player);
        BankTier tier = tier(data);
        double space = Money.round(tier.maxBalance() - data.getBankBalance());
        if (space <= 0) {
            plugin.messages().send(player, "bank.full", "max", Money.format(tier.maxBalance()));
            return false;
        }
        double value = Money.round(Math.min(amount, space));
        if (value <= 0) {
            plugin.messages().send(player, "general.invalid-amount", "input", Money.format(amount));
            return false;
        }
        if (!plugin.economy().withdraw(player, value)) {
            plugin.messages().send(player, "general.insufficient-funds", "amount", Money.format(value));
            return false;
        }
        data.setBankBalance(data.getBankBalance() + value);
        plugin.transactions().log(TransactionType.BANK_DEPOSIT, player.getUniqueId(), null, -value, "Wallet -> bank");
        plugin.messages().send(player, value < amount ? "bank.deposited-capped" : "bank.deposited",
                "amount", Money.format(value), "balance", Money.format(data.getBankBalance()));
        Sounds.play(player, plugin.getConfig().getString("sounds.bank", "ORB_PICKUP"));
        return true;
    }

    public boolean withdraw(Player player, double amount) {
        PlayerData data = plugin.players().get(player);
        double value = Money.round(amount);
        if (value <= 0 || data.getBankBalance() < value) {
            plugin.messages().send(player, "bank.insufficient", "balance", Money.format(data.getBankBalance()));
            return false;
        }
        double feePercent = player.hasPermission("servercore.bank.nofee") ? 0 : plugin.getConfig().getDouble("bank.withdraw-fee-percent", 0);
        double fee = Money.round(value * feePercent / 100D);
        data.setBankBalance(data.getBankBalance() - value);
        if (!plugin.economy().deposit(player, value - fee)) {
            data.setBankBalance(data.getBankBalance() + value);
            plugin.messages().send(player, "general.transaction-failed");
            return false;
        }
        plugin.transactions().log(TransactionType.BANK_WITHDRAW, player.getUniqueId(), null, value - fee,
                "Bank -> wallet" + (fee > 0 ? " (fee " + Money.format(fee) + ")" : ""));
        plugin.messages().send(player, fee > 0 ? "bank.withdrew-fee" : "bank.withdrew", "amount", Money.format(value - fee),
                "fee", Money.format(fee), "balance", Money.format(data.getBankBalance()));
        Sounds.play(player, plugin.getConfig().getString("sounds.bank", "ORB_PICKUP"));
        return true;
    }

    public boolean upgrade(Player player) {
        PlayerData data = plugin.players().get(player);
        BankTier next = nextTier(data);
        if (next == null) {
            plugin.messages().send(player, "bank.max-tier");
            return false;
        }
        if (next.permission() != null && !next.permission().isEmpty() && !player.hasPermission(next.permission())) {
            plugin.messages().send(player, "bank.tier-locked", "tier", next.name());
            return false;
        }
        if (!plugin.economy().withdraw(player, next.cost())) {
            plugin.messages().send(player, "general.insufficient-funds", "amount", Money.format(next.cost()));
            return false;
        }
        data.setBankTier(next.index());
        plugin.transactions().log(TransactionType.BANK_UPGRADE, player.getUniqueId(), null, -next.cost(), "Upgraded to " + Text.strip(next.name()));
        plugin.messages().send(player, "bank.upgraded", "tier", next.name(), "max", Money.format(next.maxBalance()),
                "interest", Money.percent(next.interestPercent()));
        Sounds.play(player, plugin.getConfig().getString("sounds.success", "LEVEL_UP"));
        return true;
    }

    /** Bank-to-bank transfer; the target may be offline. */
    public void transfer(Player player, UUID targetId, String targetName, double amount) {
        if (targetId.equals(player.getUniqueId())) {
            plugin.messages().send(player, "pay.self");
            return;
        }
        PlayerData data = plugin.players().get(player);
        double feePercent = player.hasPermission("servercore.bank.nofee") ? 0 : plugin.getConfig().getDouble("bank.transfer-fee-percent", 0);
        double fee = Money.round(amount * feePercent / 100D);
        double total = Money.round(amount + fee);
        if (data.getBankBalance() < total) {
            plugin.messages().send(player, "bank.insufficient", "balance", Money.format(data.getBankBalance()));
            return;
        }
        plugin.players().withData(targetId, targetName, target -> {
            if (!player.isOnline()) {
                return;
            }
            BankTier targetTier = tier(target);
            if (target.getBankBalance() + amount > targetTier.maxBalance()) {
                plugin.messages().send(player, "bank.target-full", "player", targetName);
                return;
            }
            if (data.getBankBalance() < total) {
                plugin.messages().send(player, "bank.insufficient", "balance", Money.format(data.getBankBalance()));
                return;
            }
            data.setBankBalance(data.getBankBalance() - total);
            target.setBankBalance(target.getBankBalance() + amount);
            plugin.transactions().log(TransactionType.BANK_TRANSFER_OUT, player.getUniqueId(), targetId, -total,
                    "To " + targetName + (fee > 0 ? " (fee " + Money.format(fee) + ")" : ""));
            plugin.transactions().log(TransactionType.BANK_TRANSFER_IN, targetId, player.getUniqueId(), amount, "From " + player.getName());
            plugin.messages().send(player, "bank.transferred", "amount", Money.format(amount), "player", targetName, "fee", Money.format(fee));
            Player online = Bukkit.getPlayer(targetId);
            if (online != null) {
                plugin.messages().send(online, "bank.transfer-received", "amount", Money.format(amount), "player", player.getName());
            }
        });
    }

    private void payInterest() {
        boolean requireActive = plugin.getConfig().getBoolean("bank.interest.require-active", true);
        double maxPayout = plugin.getConfig().getDouble("bank.interest.max-payout", 50000);
        double minBalance = plugin.getConfig().getDouble("bank.interest.min-balance", 100);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("servercore.bank.interest")) {
                continue;
            }
            PlayerData data = plugin.players().get(player);
            if (data.getBankBalance() < minBalance) {
                continue;
            }
            if (requireActive && plugin.activity().isAfk(player)) {
                plugin.messages().send(player, "bank.interest-afk");
                continue;
            }
            BankTier tier = tier(data);
            double interest = Money.round(data.getBankBalance() * tier.interestPercent() / 100D);
            if (maxPayout > 0) {
                interest = Math.min(interest, maxPayout);
            }
            interest = Money.round(Math.min(interest, tier.maxBalance() - data.getBankBalance()));
            if (interest <= 0) {
                continue;
            }
            data.setBankBalance(data.getBankBalance() + interest);
            plugin.transactions().log(TransactionType.BANK_INTEREST, player.getUniqueId(), null, interest,
                    Money.percent(tier.interestPercent()) + " on " + Text.strip(tier.name()));
            plugin.messages().send(player, "bank.interest", "amount", Money.format(interest), "rate", Money.percent(tier.interestPercent()),
                    "balance", Money.format(data.getBankBalance()));
        }
    }

    /** Admin: set an (offline) player's bank balance. */
    public void adminSet(UUID uuid, String name, double amount, Runnable done) {
        plugin.players().withData(uuid, name, data -> {
            double before = data.getBankBalance();
            data.setBankBalance(amount);
            plugin.transactions().log(TransactionType.ADMIN, uuid, null, data.getBankBalance() - before, "Bank balance set");
            done.run();
        });
    }
}
