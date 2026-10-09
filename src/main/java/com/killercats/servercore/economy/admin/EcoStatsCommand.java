package com.killercats.servercore.economy.admin;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.economy.auction.AuctionManager;
import com.killercats.servercore.economy.bounty.Bounty;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Text;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Economy health overview: where money is created and destroyed during the last 24 hours. */
public final class EcoStatsCommand extends BaseCommand {

    public EcoStatsCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.ecostats");
        long since = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        plugin.players().bankTotals().thenCombine(plugin.transactions().summary(since), (bank, summary) -> new Object[]{bank, summary})
                .thenAccept(result -> plugin.sync(() -> {
                    double[] bank = (double[]) result[0];
                    @SuppressWarnings("unchecked")
                    List<String[]> summary = (List<String[]>) result[1];
                    double bountyTotal = 0;
                    for (Bounty bounty : plugin.bounties().sorted()) {
                        bountyTotal += bounty.total();
                    }
                    plugin.messages().sendRaw(sender, "ecostats.header", "provider", plugin.economy().providerName(),
                            "essentials", plugin.essentials().version(), "accounts", (long) bank[0], "bank", Money.format(bank[1]),
                            "auctions", plugin.auctions().active(null, AuctionManager.Sort.NEWEST).size(),
                            "bounties", Money.format(bountyTotal), "lottery", Money.format(plugin.lottery().jackpot()));
                    for (String[] row : summary) {
                        String display;
                        try {
                            display = Text.color(TransactionType.valueOf(row[0]).display());
                        } catch (IllegalArgumentException e) {
                            display = row[0];
                        }
                        double volume = Double.parseDouble(row[2]);
                        plugin.messages().sendRaw(sender, "ecostats.entry", "type", display, "count", row[1],
                                "volume", (volume >= 0 ? Text.color("&a+") : Text.color("&c-")) + Money.format(Math.abs(volume)));
                    }
                    if (summary.isEmpty()) {
                        plugin.messages().sendRaw(sender, "general.nothing-here");
                    }
                }));
    }
}
