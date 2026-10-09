package com.killercats.servercore.economy.bounty;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.util.Money;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class BountyCommand extends BaseCommand {

    public BountyCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.bounty");
        BountyManager manager = plugin.bounties();
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set":
            case "place":
            case "add": {
                Player player = requirePlayer(sender);
                requirePermission(sender, "servercore.bounty.place");
                if (args.length < 3) {
                    usage("/" + label + " set <player> <amount>");
                }
                Player target = requireOnline(args[1]);
                manager.place(player, target, requireAmount(args[2]));
                return;
            }
            case "list":
            case "top": {
                int page = args.length > 1 ? requireInt(args[1], 1, 1000) : 1;
                List<Bounty> all = manager.sorted();
                int pages = Math.max(1, (all.size() + 9) / 10);
                plugin.messages().sendRaw(sender, "bounty.list-header", "page", page, "pages", pages);
                if (all.isEmpty()) {
                    plugin.messages().sendRaw(sender, "general.nothing-here");
                }
                for (int i = (page - 1) * 10; i < Math.min(all.size(), page * 10); i++) {
                    Bounty bounty = all.get(i);
                    plugin.messages().sendRaw(sender, "bounty.list-entry", "rank", i + 1, "player", bounty.targetName(),
                            "amount", Money.format(bounty.total()), "contributors", bounty.contributions().size());
                }
                return;
            }
            case "check": {
                if (args.length < 2) {
                    usage("/" + label + " check <player>");
                }
                Bounty bounty = manager.findByName(args[1]);
                msg(sender, "bounty.check", "player", args[1], "amount", Money.format(bounty == null ? 0 : bounty.total()));
                return;
            }
            case "remove": {
                requirePermission(sender, "servercore.bounty.admin");
                if (args.length < 2) {
                    usage("/" + label + " remove <player>");
                }
                Bounty bounty = manager.findByName(args[1]);
                if (bounty == null || !manager.remove(bounty.target())) {
                    msg(sender, "bounty.none", "player", args[1]);
                    return;
                }
                msg(sender, "bounty.removed", "player", args[1]);
                return;
            }
            default:
                plugin.messages().sendRaw(sender, "bounty.help", "label", label);
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return list("set", "list", "check", "remove");
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("list")) {
            return null;
        }
        return list();
    }
}
