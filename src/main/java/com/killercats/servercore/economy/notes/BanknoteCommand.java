package com.killercats.servercore.economy.notes;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class BanknoteCommand extends BaseCommand {

    public BanknoteCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("lookup")) {
            requirePermission(sender, "servercore.banknote.lookup");
            plugin.banknotes().lookup(sender, args[1]);
            return;
        }
        Player player = requirePlayer(sender);
        requirePermission(sender, "servercore.banknote");
        if (args.length < 1) {
            usage("/" + label + " <amount> [count]");
        }
        double amount = requireAmount(args[0]);
        int count = args.length > 1 ? requireInt(args[1], 1, plugin.getConfig().getInt("banknotes.max-per-command", 16)) : 1;
        plugin.banknotes().create(player, amount, count);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return list("1k", "10k", "100k", "lookup");
        }
        return list();
    }
}
