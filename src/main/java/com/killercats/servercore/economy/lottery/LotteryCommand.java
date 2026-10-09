package com.killercats.servercore.economy.lottery;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class LotteryCommand extends BaseCommand {

    public LotteryCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.lottery");
        LotteryManager lottery = plugin.lottery();
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "buy": {
                Player player = requirePlayer(sender);
                int amount = args.length > 1 ? requireInt(args[1], 1, 1000) : 1;
                lottery.buy(player, amount);
                return;
            }
            case "draw":
                requirePermission(sender, "servercore.lottery.admin");
                lottery.draw();
                return;
            case "addpot": {
                requirePermission(sender, "servercore.lottery.admin");
                if (args.length < 2) {
                    usage("/" + label + " addpot <amount>");
                }
                lottery.addToRollover(requireAmount(args[1]));
                msg(sender, "lottery.pot-added", "pot", Money.format(lottery.jackpot()));
                return;
            }
            case "info":
            default:
                plugin.messages().sendRaw(sender, "lottery.info", "pot", Money.format(lottery.jackpot()),
                        "time", TimeUtil.formatDuration(lottery.timeLeft()), "price", Money.format(lottery.ticketPrice()),
                        "tickets", sender instanceof Player ? lottery.tickets(((Player) sender).getUniqueId()) : 0,
                        "total", lottery.totalTickets(), "label", label);
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return sender.hasPermission("servercore.lottery.admin") ? list("buy", "info", "draw", "addpot") : list("buy", "info");
        }
        return list();
    }
}
