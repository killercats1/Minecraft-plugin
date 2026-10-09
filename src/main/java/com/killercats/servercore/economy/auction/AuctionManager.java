package com.killercats.servercore.economy.auction;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Inventories;
import com.killercats.servercore.util.ItemSerializer;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Perms;
import com.killercats.servercore.util.Sounds;
import com.killercats.servercore.util.Text;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Player auction house. The in-memory map is the source of truth and is only changed on the main
 * thread, so two players can never buy the same listing; every change is persisted afterwards.
 */
public final class AuctionManager implements Listener {

    public enum Sort {
        NEWEST, PRICE_LOW, PRICE_HIGH, ENDING_SOON;

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final ServerCore plugin;
    private final Map<Long, AuctionListing> listings = new HashMap<Long, AuctionListing>();
    private final String upsertSql;
    private long nextId;
    private BukkitTask expireTask;

    public AuctionManager(ServerCore plugin) {
        this.plugin = plugin;
        this.upsertSql = plugin.database().upsert("sc_auctions", "id", "seller", "seller_name", "item", "price", "created", "expires",
                "state", "buyer", "collected");
        load();
        expireTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 200L, 200L);
    }

    private void load() {
        try {
            nextId = plugin.database().query("SELECT MAX(id) AS m FROM sc_auctions", rs -> rs.next() ? rs.getLong("m") : 0L)
                    .get(15, TimeUnit.SECONDS) + 1;
            List<AuctionListing> loaded = plugin.database().query(
                    "SELECT * FROM sc_auctions WHERE state = 'ACTIVE' OR (collected = 0 AND state IN ('EXPIRED', 'CANCELLED'))", rs -> {
                        List<AuctionListing> list = new ArrayList<AuctionListing>();
                        while (rs.next()) {
                            try {
                                String buyer = rs.getString("buyer");
                                list.add(new AuctionListing(rs.getLong("id"), UUID.fromString(rs.getString("seller")), rs.getString("seller_name"),
                                        ItemSerializer.fromBase64(rs.getString("item")), rs.getDouble("price"), rs.getLong("created"),
                                        rs.getLong("expires"), AuctionListing.State.valueOf(rs.getString("state")),
                                        buyer == null ? null : UUID.fromString(buyer), rs.getInt("collected") == 1));
                            } catch (Exception e) {
                                plugin.getLogger().warning("Skipping broken auction listing #" + rs.getLong("id") + ": " + e.getMessage());
                            }
                        }
                        return list;
                    }).get(30, TimeUnit.SECONDS);
            for (AuctionListing listing : loaded) {
                listings.put(listing.id(), listing);
            }
            plugin.getLogger().info("Loaded " + loaded.size() + " auction listings.");
        } catch (Exception e) {
            plugin.getLogger().severe("Could not load auction house: " + e.getMessage());
            nextId = System.currentTimeMillis();
        }
    }

    public void shutdown() {
        if (expireTask != null) {
            expireTask.cancel();
        }
    }

    private void save(AuctionListing l) {
        plugin.database().update(upsertSql, l.id(), l.seller().toString(), l.sellerName(), ItemSerializer.toBase64(l.item()), l.price(),
                l.created(), l.expires(), l.state().name(), l.buyer() == null ? null : l.buyer().toString(), l.collected() ? 1 : 0);
    }

    private void forget(AuctionListing l) {
        if (!l.isActive() && (l.collected() || l.state() == AuctionListing.State.SOLD)) {
            listings.remove(l.id());
        }
    }

    public AuctionListing get(long id) {
        return listings.get(id);
    }

    public List<AuctionListing> active(UUID sellerFilter, Sort sort) {
        List<AuctionListing> list = new ArrayList<AuctionListing>();
        for (AuctionListing l : listings.values()) {
            if (l.isActive() && (sellerFilter == null || l.seller().equals(sellerFilter))) {
                list.add(l);
            }
        }
        Comparator<AuctionListing> comparator;
        switch (sort) {
            case PRICE_LOW:
                comparator = Comparator.comparingDouble(AuctionListing::price);
                break;
            case PRICE_HIGH:
                comparator = Comparator.comparingDouble(AuctionListing::price).reversed();
                break;
            case ENDING_SOON:
                comparator = Comparator.comparingLong(AuctionListing::expires);
                break;
            default:
                comparator = Comparator.comparingLong(AuctionListing::created).reversed();
                break;
        }
        list.sort(comparator);
        return list;
    }

    public List<AuctionListing> claimable(UUID seller) {
        List<AuctionListing> list = new ArrayList<AuctionListing>();
        for (AuctionListing l : listings.values()) {
            if (l.seller().equals(seller) && !l.collected()
                    && (l.state() == AuctionListing.State.EXPIRED || l.state() == AuctionListing.State.CANCELLED)) {
                list.add(l);
            }
        }
        return list;
    }

    public int limit(Player player) {
        int best = plugin.getConfig().getInt("auction.default-listing-limit", 5);
        for (int value : plugin.getConfig().getIntegerList("auction.limit-permissions")) {
            if (value > best && Perms.explicit(player, "servercore.auction.limit." + value)) {
                best = value;
            }
        }
        return best;
    }

    public void list(Player player, double price) {
        ItemStack hand = player.getItemInHand();
        if (hand == null || hand.getType() == Material.AIR) {
            plugin.messages().send(player, "auction.hold-item");
            return;
        }
        for (String blocked : plugin.getConfig().getStringList("auction.blacklist")) {
            if (hand.getType().name().equalsIgnoreCase(blocked)) {
                plugin.messages().send(player, "auction.blacklisted");
                return;
            }
        }
        double min = plugin.getConfig().getDouble("auction.min-price", 1);
        double max = plugin.getConfig().getDouble("auction.max-price", 1_000_000_000);
        if (price < min || price > max) {
            plugin.messages().send(player, "auction.price-limits", "min", Money.format(min), "max", Money.format(max));
            return;
        }
        int limit = limit(player);
        if (active(player.getUniqueId(), Sort.NEWEST).size() >= limit) {
            plugin.messages().send(player, "auction.limit-reached", "limit", limit);
            return;
        }
        double fee = player.hasPermission("servercore.auction.nofee") ? 0
                : Money.round(price * plugin.getConfig().getDouble("auction.listing-fee-percent", 2) / 100D);
        if (fee > 0 && !plugin.economy().withdraw(player, fee)) {
            plugin.messages().send(player, "general.insufficient-funds", "amount", Money.format(fee));
            return;
        }
        ItemStack item = hand.clone();
        player.setItemInHand(null);
        long now = System.currentTimeMillis();
        long duration = plugin.getConfig().getLong("auction.duration-hours", 48) * 60L * 60 * 1000;
        AuctionListing listing = new AuctionListing(nextId++, player.getUniqueId(), player.getName(), item, price, now, now + duration,
                AuctionListing.State.ACTIVE, null, false);
        listings.put(listing.id(), listing);
        save(listing);
        if (fee > 0) {
            plugin.transactions().log(TransactionType.AUCTION_FEE, player.getUniqueId(), null, -fee, "Listing #" + listing.id());
        }
        plugin.messages().send(player, "auction.listed", "item", describe(item), "price", Money.format(price), "fee", Money.format(fee),
                "time", TimeUtil.formatDuration(duration));
        if (plugin.getConfig().getBoolean("auction.broadcast-new-listings", true)) {
            String message = plugin.messages().prefix() + plugin.messages().get("auction.broadcast", "player", player.getName(),
                    "item", describe(item), "price", Money.format(price));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    online.sendMessage(message);
                }
            }
        }
    }

    public boolean buy(Player player, long id) {
        AuctionListing listing = listings.get(id);
        if (listing == null || !listing.isActive() || listing.expires() <= System.currentTimeMillis()) {
            plugin.messages().send(player, "auction.unavailable");
            return false;
        }
        if (listing.seller().equals(player.getUniqueId())) {
            plugin.messages().send(player, "auction.own-listing");
            return false;
        }
        ItemStack item = listing.item();
        if (!Inventories.canFit(player, item)) {
            plugin.messages().send(player, "general.inventory-full");
            return false;
        }
        if (!plugin.economy().withdraw(player, listing.price())) {
            plugin.messages().send(player, "general.insufficient-funds", "amount", Money.format(listing.price()));
            return false;
        }
        double tax = Money.round(listing.price() * plugin.getConfig().getDouble("auction.sales-tax-percent", 5) / 100D);
        double earnings = Money.round(listing.price() - tax);
        if (!plugin.economy().deposit(Bukkit.getOfflinePlayer(listing.seller()), earnings)) {
            plugin.economy().deposit(player, listing.price());
            plugin.messages().send(player, "general.transaction-failed");
            return false;
        }
        listing.setState(AuctionListing.State.SOLD);
        listing.setBuyer(player.getUniqueId());
        listing.setCollected(true);
        save(listing);
        forget(listing);
        Inventories.giveOrDrop(player, item);
        plugin.transactions().log(TransactionType.AUCTION_PURCHASE, player.getUniqueId(), listing.seller(), -listing.price(),
                describe(item) + " from " + listing.sellerName());
        plugin.transactions().log(TransactionType.AUCTION_SALE, listing.seller(), player.getUniqueId(), earnings,
                describe(item) + " to " + player.getName() + (tax > 0 ? " (tax " + Money.format(tax) + ")" : ""));
        plugin.messages().send(player, "auction.bought", "item", describe(item), "price", Money.format(listing.price()), "seller", listing.sellerName());
        Sounds.play(player, plugin.getConfig().getString("sounds.success", "LEVEL_UP"));
        Player seller = Bukkit.getPlayer(listing.seller());
        if (seller != null) {
            plugin.messages().send(seller, "auction.sold", "item", describe(item), "buyer", player.getName(), "amount", Money.format(earnings));
            Sounds.play(seller, plugin.getConfig().getString("sounds.money-received", "ORB_PICKUP"));
        }
        return true;
    }

    /** Cancels a listing (seller or admin). The item is returned directly or put into the claim list. */
    public boolean cancel(Player player, long id) {
        AuctionListing listing = listings.get(id);
        if (listing == null || !listing.isActive()) {
            plugin.messages().send(player, "auction.unavailable");
            return false;
        }
        boolean own = listing.seller().equals(player.getUniqueId());
        if (!own && !player.hasPermission("servercore.auction.admin")) {
            plugin.messages().send(player, "general.no-permission");
            return false;
        }
        listing.setState(AuctionListing.State.CANCELLED);
        if (own && Inventories.canFit(player, listing.item())) {
            Inventories.giveOrDrop(player, listing.item());
            listing.setCollected(true);
        }
        save(listing);
        forget(listing);
        plugin.messages().send(player, listing.collected() ? "auction.cancelled" : "auction.cancelled-claim");
        return true;
    }

    public void claim(Player player) {
        List<AuctionListing> list = claimable(player.getUniqueId());
        if (list.isEmpty()) {
            plugin.messages().send(player, "auction.nothing-to-claim");
            return;
        }
        int claimed = 0;
        for (AuctionListing listing : list) {
            ItemStack item = listing.item();
            if (!Inventories.canFit(player, item)) {
                plugin.messages().send(player, "general.inventory-full");
                break;
            }
            Inventories.giveOrDrop(player, item);
            listing.setCollected(true);
            save(listing);
            forget(listing);
            claimed++;
        }
        if (claimed > 0) {
            plugin.messages().send(player, "auction.claimed", "count", claimed);
        }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (AuctionListing listing : new ArrayList<AuctionListing>(listings.values())) {
            if (listing.isActive() && listing.expires() <= now) {
                listing.setState(AuctionListing.State.EXPIRED);
                save(listing);
                Player seller = Bukkit.getPlayer(listing.seller());
                if (seller != null) {
                    plugin.messages().send(seller, "auction.expired", "item", describe(listing.item()));
                }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                int count = claimable(player.getUniqueId()).size();
                if (count > 0) {
                    plugin.messages().send(player, "auction.claim-reminder", "count", count);
                }
            }
        }, 80L);
    }

    public static String describe(ItemStack item) {
        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? item.getItemMeta().getDisplayName()
                : Text.capitalize(item.getType().name().toLowerCase(Locale.ROOT));
        return item.getAmount() + "x " + Text.strip(name);
    }
}
