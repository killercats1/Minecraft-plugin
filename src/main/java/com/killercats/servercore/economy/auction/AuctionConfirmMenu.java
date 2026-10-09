package com.killercats.servercore.economy.auction;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.gui.Menu;
import com.killercats.servercore.util.ItemBuilder;
import com.killercats.servercore.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class AuctionConfirmMenu extends Menu {

    public AuctionConfirmMenu(ServerCore plugin, Player viewer, long id, int page, boolean mine, AuctionManager.Sort sort) {
        super(plugin.messages().get("auction.confirm-title"), 3);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, ItemBuilder.filler());
        }
        AuctionListing listing = plugin.auctions().get(id);
        if (listing == null || !listing.isActive()) {
            set(13, new ItemBuilder(Material.BARRIER).name("&cThis listing is no longer available").build(), null);
        } else {
            set(13, listing.item(), null);
            set(11, new ItemBuilder(Material.WOOL, 1, (short) 5).name("&a&lConfirm purchase")
                    .lore("&7Price: &a" + Money.format(listing.price()), "&7Seller: &f" + listing.sellerName()).build(), (p, e) -> {
                plugin.auctions().buy(p, id);
                Bukkit.getScheduler().runTask(plugin, () -> new AuctionMenu(plugin, p, page, mine, sort).open(p));
            });
        }
        set(15, new ItemBuilder(Material.WOOL, 1, (short) 14).name("&c&lCancel").build(),
                (p, e) -> Bukkit.getScheduler().runTask(plugin, () -> new AuctionMenu(plugin, p, page, mine, sort).open(p)));
    }
}
