package com.killercats.servercore.economy.pay;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.CommandFail;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Sounds;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replacement for Essentials' /pay with taxes, cooldowns, confirmation for large amounts,
 * offline payments, a payment toggle and full logging. Essentials automatically defers its
 * /pay to this command; set pay.override-essentials to false to hand /pay back to Essentials.
 */
public final class PayCommand extends BaseCommand {

    private static final class Pending {
        final UUID target;
        final String targetName;
        final double amount;
        final long expires;

        Pending(UUID target, String targetName, double amount, long expires) {
            this.target = target;
            this.targetName = targetName;
            this.amount = amount;
            this.expires = expires;
        }
    }

    private final Map<UUID, Long> cooldowns = new HashMap<UUID, Long>();
    private final Map<UUID, Pending> pending = new HashMap<UUID, Pending>();

    public PayCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = requirePlayer(sender);
        if (!plugin.getConfig().getBoolean("pay.override-essentials", true)) {
            player.performCommand("epay " + Text.join(args, 0));
            return;
        }
        requirePermission(sender, "servercore.pay");
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            Pending p = pending.remove(player.getUniqueId());
            if (p == null || p.expires < System.currentTimeMillis()) {
                throw new CommandFail("pay.nothing-to-confirm");
            }
            transfer(player, p.target, p.targetName, p.amount);
            return;
        }
        if (args.length < 2) {
            usage("/" + label + " <player> <amount>");
        }
        double amount = requireAmount(args[1]);
        double min = plugin.getConfig().getDouble("pay.min-amount", 1);
        if (amount < min) {
            throw new CommandFail("pay.below-minimum", "min", Money.format(min));
        }
        long cooldown = plugin.getConfig().getLong("pay.cooldown-seconds", 3) * 1000L;
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < cooldown && !player.hasPermission("servercore.pay.bypasscooldown")) {
            throw new CommandFail("general.cooldown", "time", ((cooldown - (System.currentTimeMillis() - last)) / 1000 + 1) + "s");
        }
        if (!plugin.economy().has(player, amount)) {
            throw new CommandFail("general.insufficient-funds", "amount", Money.format(amount));
        }

        Player online = Bukkit.getPlayer(args[0]);
        if (online != null) {
            prepare(player, online.getUniqueId(), online.getName(), amount);
            return;
        }
        if (!plugin.getConfig().getBoolean("pay.allow-offline", true)) {
            throw new CommandFail("general.player-not-found", "player", args[0]);
        }
        String name = args[0];
        plugin.players().lookupUuid(name).thenAccept(uuid -> plugin.sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (uuid == null) {
                msg(player, "general.player-not-found", "player", name);
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
            prepare(player, uuid, target.getName() == null ? name : target.getName(), amount);
        }));
    }

    private void prepare(Player player, UUID target, String targetName, double amount) {
        if (target.equals(player.getUniqueId())) {
            msg(player, "pay.self");
            return;
        }
        double confirmAbove = plugin.getConfig().getDouble("pay.confirm-above", 100000);
        if (confirmAbove > 0 && amount >= confirmAbove) {
            pending.put(player.getUniqueId(), new Pending(target, targetName, amount, System.currentTimeMillis() + 30_000L));
            msg(player, "pay.confirm", "amount", Money.format(amount), "player", targetName);
            return;
        }
        transfer(player, target, targetName, amount);
    }

    private void transfer(Player player, UUID targetId, String targetName, double amount) {
        plugin.players().withData(targetId, targetName, data -> {
            if (!player.isOnline()) {
                return;
            }
            if (!data.isAcceptPay() && !player.hasPermission("servercore.pay.bypasstoggle")) {
                msg(player, "pay.target-disabled", "player", targetName);
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
            double taxPercent = player.hasPermission("servercore.pay.notax") ? 0 : plugin.getConfig().getDouble("pay.tax-percent", 0);
            double tax = Money.round(amount * taxPercent / 100D);
            double received = Money.round(amount - tax);
            if (!plugin.economy().withdraw(player, amount)) {
                msg(player, "general.insufficient-funds", "amount", Money.format(amount));
                return;
            }
            if (!plugin.economy().deposit(target, received)) {
                plugin.economy().deposit(player, amount);
                msg(player, "general.transaction-failed");
                return;
            }
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
            plugin.transactions().log(TransactionType.PAY_SENT, player.getUniqueId(), targetId, -amount,
                    "To " + targetName + (tax > 0 ? " (tax " + Money.format(tax) + ")" : ""));
            plugin.transactions().log(TransactionType.PAY_RECEIVED, targetId, player.getUniqueId(), received, "From " + player.getName());
            if (tax > 0) {
                msg(player, "pay.sent-taxed", "amount", Money.format(amount), "player", targetName, "tax", Money.format(tax),
                        "received", Money.format(received));
            } else {
                msg(player, "pay.sent", "amount", Money.format(amount), "player", targetName);
            }
            Player online = Bukkit.getPlayer(targetId);
            if (online != null) {
                msg(online, "pay.received", "amount", Money.format(received), "player", player.getName());
                Sounds.play(online, plugin.getConfig().getString("sounds.money-received", "ORB_PICKUP"));
            }
        });
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return null;
        }
        if (args.length == 2) {
            return list("100", "1k", "10k", "100k");
        }
        return list();
    }
}
