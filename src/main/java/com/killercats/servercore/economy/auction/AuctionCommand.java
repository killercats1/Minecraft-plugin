package com.killercats.servercore.economy.auction;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class AuctionCommand extends BaseCommand {

    public AuctionCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = requirePlayer(sender);
        requirePermission(sender, "servercore.auction");
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "":
                new AuctionMenu(plugin, player, 0, false, AuctionManager.Sort.NEWEST).open(player);
                return;
            case "sell":
            case "list":
                requirePermission(sender, "servercore.auction.sell");
                if (args.length < 2) {
                    usage("/" + label + " sell <price>");
                }
                plugin.auctions().list(player, requireAmount(args[1]));
                return;
            case "mine":
            case "listings":
                new AuctionMenu(plugin, player, 0, true, AuctionManager.Sort.NEWEST).open(player);
                return;
            case "claim":
            case "collect":
                plugin.auctions().claim(player);
                return;
            default:
                plugin.messages().sendRaw(sender, "auction.help", "label", label);
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? list("sell", "mine", "claim") : list();
    }
}
