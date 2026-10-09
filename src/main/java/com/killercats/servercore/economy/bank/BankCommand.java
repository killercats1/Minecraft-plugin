package com.killercats.servercore.economy.bank;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.CommandFail;
import com.killercats.servercore.data.PlayerData;
import com.killercats.servercore.data.PlayerDataManager;
import com.killercats.servercore.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class BankCommand extends BaseCommand {

    public BankCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("top")) {
            requirePermission(sender, "servercore.bank.top");
            plugin.players().topBank(10).thenAccept(top -> plugin.sync(() -> {
                plugin.messages().sendRaw(sender, "bank.top-header");
                int rank = 1;
                for (PlayerDataManager.TopEntry entry : top) {
                    plugin.messages().sendRaw(sender, "bank.top-entry", "rank", rank++, "player", entry.name, "amount", Money.format(entry.amount));
                }
                if (top.isEmpty()) {
                    plugin.messages().sendRaw(sender, "general.nothing-here");
                }
            }));
            return;
        }
        Player player = requirePlayer(sender);
        requirePermission(sender, "servercore.bank");
        PlayerData data = plugin.players().get(player);
        BankManager bank = plugin.bank();
        switch (sub) {
            case "":
                new BankMenu(plugin, player).open(player);
                return;
            case "balance":
            case "bal":
            case "info": {
                BankTier tier = bank.tier(data);
                plugin.messages().sendRaw(sender, "bank.info", "balance", Money.format(data.getBankBalance()), "tier", tier.name(),
                        "max", Money.format(tier.maxBalance()), "interest", Money.percent(tier.interestPercent()),
                        "wallet", Money.format(plugin.economy().balance(player)));
                return;
            }
            case "deposit":
            case "d": {
                if (args.length < 2) {
                    usage("/" + label + " deposit <amount|all>");
                }
                double amount = args[1].equalsIgnoreCase("all") ? plugin.economy().balance(player) : requireAmount(args[1]);
                if (amount <= 0) {
                    throw new CommandFail("general.insufficient-funds", "amount", Money.format(0));
                }
                bank.deposit(player, amount);
                return;
            }
            case "withdraw":
            case "w": {
                if (args.length < 2) {
                    usage("/" + label + " withdraw <amount|all>");
                }
                double amount = args[1].equalsIgnoreCase("all") ? data.getBankBalance() : requireAmount(args[1]);
                bank.withdraw(player, amount);
                return;
            }
            case "upgrade":
                bank.upgrade(player);
                return;
            case "tiers":
                plugin.messages().sendRaw(sender, "bank.tiers-header");
                for (BankTier tier : bank.tiers()) {
                    plugin.messages().sendRaw(sender, "bank.tiers-entry", "tier", tier.name(), "cost", Money.format(tier.cost()),
                            "max", Money.format(tier.maxBalance()), "interest", Money.percent(tier.interestPercent()),
                            "current", tier.index() == bank.tier(data).index() ? plugin.messages().get("bank.current-marker") : "");
                }
                return;
            case "transfer": {
                requirePermission(sender, "servercore.bank.transfer");
                if (args.length < 3) {
                    usage("/" + label + " transfer <player> <amount>");
                }
                double amount = requireAmount(args[2]);
                Player online = Bukkit.getPlayer(args[1]);
                if (online != null) {
                    bank.transfer(player, online.getUniqueId(), online.getName(), amount);
                    return;
                }
                String name = args[1];
                plugin.players().lookupUuid(name).thenAccept(uuid -> plugin.sync(() -> {
                    if (uuid == null) {
                        msg(player, "general.player-not-found", "player", name);
                    } else if (player.isOnline()) {
                        bank.transfer(player, uuid, name, amount);
                    }
                }));
                return;
            }
            default:
                plugin.messages().sendRaw(sender, "bank.help", "label", label);
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return list("balance", "deposit", "withdraw", "upgrade", "tiers", "transfer", "top");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("deposit") || args[0].equalsIgnoreCase("withdraw"))) {
            return list("all", "1k", "10k", "100k");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("transfer")) {
            return null;
        }
        return list();
    }
}
