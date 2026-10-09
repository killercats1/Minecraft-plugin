package com.killercats.servercore.economy.admin;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.CommandFail;
import com.killercats.servercore.data.PlayerData;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Money;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

public final class EcoAdminCommand extends BaseCommand {

    public EcoAdminCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.ecoadmin");
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "bank": {
                if (args.length < 4) {
                    usage("/" + label + " bank <set|give|take> <player> <amount>");
                }
                String action = args[1].toLowerCase(Locale.ROOT);
                String name = args[2];
                double amount = args[3].equals("0") ? 0 : requireAmount(args[3]);
                if (!action.equals("set") && !action.equals("give") && !action.equals("take")) {
                    usage("/" + label + " bank <set|give|take> <player> <amount>");
                }
                plugin.players().lookupUuid(name).thenAccept(uuid -> plugin.sync(() -> {
                    if (uuid == null) {
                        msg(sender, "general.player-not-found", "player", name);
                        return;
                    }
                    plugin.players().withData(uuid, name, data -> {
                        double before = data.getBankBalance();
                        double after = action.equals("set") ? amount : action.equals("give") ? before + amount : before - amount;
                        data.setBankBalance(after);
                        plugin.transactions().log(TransactionType.ADMIN, uuid, null, data.getBankBalance() - before,
                                "Bank " + action + " by " + sender.getName());
                        msg(sender, "ecoadmin.bank-updated", "player", name, "balance", Money.format(data.getBankBalance()));
                    });
                }));
                return;
            }
            case "resetdaily": {
                if (args.length < 2) {
                    usage("/" + label + " resetdaily <player>");
                }
                String name = args[1];
                plugin.players().lookupUuid(name).thenAccept(uuid -> plugin.sync(() -> {
                    if (uuid == null) {
                        msg(sender, "general.player-not-found", "player", name);
                        return;
                    }
                    plugin.players().withData(uuid, name, (PlayerData data) -> {
                        data.setLastDaily(0);
                        msg(sender, "ecoadmin.daily-reset", "player", name);
                    });
                }));
                return;
            }
            case "sellreset":
                plugin.sell().resetDynamic();
                msg(sender, "ecoadmin.sell-reset");
                return;
            case "purgelogs": {
                if (args.length < 2) {
                    usage("/" + label + " purgelogs <days>");
                }
                int days = requireInt(args[1], 1, 36500);
                plugin.transactions().purgeOlderThan(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)
                        .thenAccept(rows -> plugin.sync(() -> msg(sender, "ecoadmin.purged", "rows", rows, "days", days)));
                return;
            }
            default:
                throw new CommandFail("general.usage", "usage", "/" + label + " <bank|resetdaily|sellreset|purgelogs>");
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return list("bank", "resetdaily", "sellreset", "purgelogs");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bank")) {
            return list("set", "give", "take");
        }
        if ((args.length == 3 && args[0].equalsIgnoreCase("bank")) || (args.length == 2 && args[0].equalsIgnoreCase("resetdaily"))) {
            return null;
        }
        return list();
    }
}
