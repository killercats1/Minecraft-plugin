package com.killercats.servercore.economy.admin;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.economy.TransactionLogger;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Text;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class TransactionsCommand extends BaseCommand {

    private static final int PAGE_SIZE = 10;

    public TransactionsCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.transactions");
        String targetName = null;
        int page = 1;
        for (String arg : args) {
            if (arg.matches("\\d+")) {
                page = requireInt(arg, 1, 100000);
            } else {
                targetName = arg;
            }
        }
        if (targetName == null) {
            Player player = requirePlayer(sender);
            show(sender, player.getUniqueId(), player.getName(), page);
            return;
        }
        requirePermission(sender, "servercore.transactions.others");
        final String name = targetName;
        final int finalPage = page;
        plugin.players().lookupUuid(name).thenAccept(uuid -> plugin.sync(() -> {
            if (uuid == null) {
                msg(sender, "general.player-not-found", "player", name);
            } else {
                show(sender, uuid, name, finalPage);
            }
        }));
    }

    private void show(CommandSender sender, UUID uuid, String name, int page) {
        plugin.transactions().history(uuid, page, PAGE_SIZE).thenAccept(entries -> plugin.sync(() -> {
            plugin.messages().sendRaw(sender, "transactions.header", "player", name, "page", page);
            if (entries.isEmpty()) {
                plugin.messages().sendRaw(sender, "general.nothing-here");
                return;
            }
            for (TransactionLogger.Entry entry : entries) {
                String amount = (entry.amount >= 0 ? "&a+" : "&c-") + Money.format(Math.abs(entry.amount));
                plugin.messages().sendRaw(sender, "transactions.entry", "date", TimeUtil.formatDate(entry.time),
                        "type", Text.color(entry.type.display()), "amount", Text.color(amount), "details", entry.details == null ? "" : entry.details);
            }
            plugin.messages().sendRaw(sender, "transactions.footer", "next", page + 1);
        }));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 && sender.hasPermission("servercore.transactions.others") ? null : list();
    }
}
