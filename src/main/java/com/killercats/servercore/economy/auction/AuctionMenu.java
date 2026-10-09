package com.killercats.servercore.economy.auction;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.gui.Menu;
import com.killercats.servercore.util.ItemBuilder;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Text;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

public final class AuctionMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final ServerCore plugin;
    private final Player viewer;
    private final int page;
    private final boolean mine;
    private final AuctionManager.Sort sort;

    public AuctionMenu(ServerCore plugin, Player viewer, int page, boolean mine, AuctionManager.Sort sort) {
        super(plugin.messages().get(mine ? "auction.menu-title-mine" : "auction.menu-title", "page", page + 1), 6);
        this.plugin = plugin;
        this.viewer = viewer;
        this.page = page;
        this.mine = mine;
        this.sort = sort;
        render();
    }

    private void reopen(int newPage, boolean newMine, AuctionManager.Sort newSort) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                new AuctionMenu(plugin, viewer, newPage, newMine, newSort).open(viewer);
            }
        });
    }

    private void render() {
        AuctionManager manager = plugin.auctions();
        List<AuctionListing> listings = manager.active(mine ? viewer.getUniqueId() : null, sort);
        int pages = Math.max(1, (listings.size() + PER_PAGE - 1) / PER_PAGE);
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < listings.size(); i++) {
            AuctionListing listing = listings.get(start + i);
            boolean own = listing.seller().equals(viewer.getUniqueId());
            List<String> lore = plugin.messages().getList(own ? "auction.listing-lore-own" : "auction.listing-lore",
                    "seller", listing.sellerName(), "price", Money.format(listing.price()),
                    "time", TimeUtil.formatDuration(listing.expires() - System.currentTimeMillis()), "id", listing.id());
            final long id = listing.id();
            set(i, new ItemBuilder(listing.item()).addLore(lore).build(), (p, e) -> {
                if (own || (p.hasPermission("servercore.auction.admin") && e.getClick() == ClickType.SHIFT_RIGHT)) {
                    if (e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT) {
                        plugin.auctions().cancel(p, id);
                        reopen(page, mine, sort);
                    }
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> new AuctionConfirmMenu(plugin, p, id, page, mine, sort).open(p));
            });
        }
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, ItemBuilder.filler());
        }
        if (page > 0) {
            set(45, new ItemBuilder(Material.ARROW).name("&ePrevious page").build(), (p, e) -> reopen(page - 1, mine, sort));
        }
        if (page + 1 < pages) {
            set(53, new ItemBuilder(Material.ARROW).name("&eNext page").build(), (p, e) -> reopen(page + 1, mine, sort));
        }
        set(47, new ItemBuilder(Material.HOPPER).name("&bSort: &f" + Text.capitalize(sort.name()))
                .lore("&7Click to change").build(), (p, e) -> reopen(0, mine, sort.next()));
        set(48, new ItemBuilder(mine ? Material.CHEST : Material.ENDER_CHEST).name(mine ? "&eAll listings" : "&eMy listings")
                .lore("&7You have &f" + manager.active(viewer.getUniqueId(), sort).size() + "&7/&f" + manager.limit(viewer) + " &7listings").build(),
                (p, e) -> reopen(0, !mine, sort));
        set(49, new ItemBuilder(Material.BOOK).name("&6&lAuction House").lore(
                "&7Listings: &f" + listings.size(),
                "&7Page: &f" + (page + 1) + "/" + pages,
                "&7Balance: &a" + Money.format(plugin.economy().balance(viewer)),
                "",
                "&7Sell the item in your hand:",
                "&f/ah sell <price>",
                "&7Sales tax: &c" + Money.percent(plugin.getConfig().getDouble("auction.sales-tax-percent", 5)),
                "&7Listing fee: &c" + Money.percent(plugin.getConfig().getDouble("auction.listing-fee-percent", 2))).build(), null);
        int claimable = manager.claimable(viewer.getUniqueId()).size();
        set(50, new ItemBuilder(Material.STORAGE_MINECART).name("&aCollect items &7(" + claimable + ")")
                .lore("&7Expired and cancelled listings", "&eClick to collect").build(), (p, e) -> {
            plugin.auctions().claim(p);
            reopen(page, mine, sort);
        });
        set(51, new ItemBuilder(Material.WATCH).name("&eRefresh").build(), (p, e) -> reopen(page, mine, sort));
    }
}
