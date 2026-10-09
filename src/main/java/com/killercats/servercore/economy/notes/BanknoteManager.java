package com.killercats.servercore.economy.notes;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Inventories;
import com.killercats.servercore.util.ItemBuilder;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Sounds;
import com.killercats.servercore.util.Text;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Physical money: players turn wallet money into tradeable paper notes. Every note carries a random
 * id registered in the database and can be redeemed exactly once, so duplicated notes (dupe
 * glitches, creative copies) are worthless and reported to staff.
 */
public final class BanknoteManager {

    private static final String ID_PREFIX = "Note #";
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final ServerCore plugin;
    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> redeeming = new HashSet<UUID>();

    public BanknoteManager(ServerCore plugin) {
        this.plugin = plugin;
    }

    private String newId() {
        char[] id = new char[12];
        for (int i = 0; i < id.length; i++) {
            id[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(id);
    }

    public ItemStack createItem(String id, double amount, String creator) {
        Material material = Material.matchMaterial(plugin.getConfig().getString("banknotes.material", "PAPER"));
        List<String> lore = new ArrayList<String>();
        for (String line : plugin.getConfig().getStringList("banknotes.lore")) {
            lore.add(Text.replace(line, "amount", Money.format(amount), "creator", creator, "date", TimeUtil.formatDate(System.currentTimeMillis())));
        }
        lore.add("&8" + ID_PREFIX + id);
        return new ItemBuilder(material == null ? Material.PAPER : material)
                .name(Text.replace(plugin.getConfig().getString("banknotes.name", "&a&lBanknote &7({amount})"), "amount", Money.format(amount)))
                .lore(lore).hideFlags().build();
    }

    /** Extracts the note id from an item, or null if the item is not a banknote. */
    public static String readId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore() || meta.getLore().isEmpty()) {
            return null;
        }
        String last = Text.strip(meta.getLore().get(meta.getLore().size() - 1));
        return last.startsWith(ID_PREFIX) ? last.substring(ID_PREFIX.length()).trim() : null;
    }

    public void create(Player player, double amount, int count) {
        double min = plugin.getConfig().getDouble("banknotes.min-amount", 10);
        double max = plugin.getConfig().getDouble("banknotes.max-amount", 10_000_000);
        if (amount < min || amount > max) {
            plugin.messages().send(player, "banknote.limits", "min", Money.format(min), "max", Money.format(max));
            return;
        }
        if (Inventories.freeSlots(player) < count) {
            plugin.messages().send(player, "general.inventory-full");
            return;
        }
        double feePercent = player.hasPermission("servercore.banknote.nofee") ? 0 : plugin.getConfig().getDouble("banknotes.fee-percent", 0);
        double fee = Money.round(amount * count * feePercent / 100D);
        double total = Money.round(amount * count + fee);
        if (!plugin.economy().withdraw(player, total)) {
            plugin.messages().send(player, "general.insufficient-funds", "amount", Money.format(total));
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            String id = newId();
            // Registered before any redeem query can run, because the database queue is ordered.
            plugin.database().update("INSERT INTO sc_banknotes (id, amount, creator, created, redeemed, redeemed_at) VALUES (?, ?, ?, ?, 0, 0)",
                    id, amount, player.getUniqueId().toString(), now);
            Inventories.giveOrDrop(player, createItem(id, amount, player.getName()));
        }
        plugin.transactions().log(TransactionType.NOTE_CREATE, player.getUniqueId(), null, -total,
                count + "x " + Money.format(amount) + (fee > 0 ? " (fee " + Money.format(fee) + ")" : ""));
        plugin.messages().send(player, "banknote.created", "count", count, "amount", Money.format(amount), "total", Money.format(total));
    }

    /** Redeems the note in the player's hand. The item is taken first, then claimed atomically in the database. */
    public void redeemHand(Player player) {
        ItemStack hand = player.getItemInHand();
        String id = readId(hand);
        if (id == null || redeeming.contains(player.getUniqueId())) {
            return;
        }
        redeeming.add(player.getUniqueId());
        ItemStack single = hand.clone();
        single.setAmount(1);
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
        } else {
            player.setItemInHand(null);
        }
        UUID uuid = player.getUniqueId();
        plugin.database().query("SELECT amount, redeemed, redeemed_by FROM sc_banknotes WHERE id = ?",
                rs -> rs.next() ? new Object[]{rs.getDouble("amount"), rs.getInt("redeemed"), rs.getString("redeemed_by")} : null, id)
                .thenCompose(row -> {
                    if (row == null || (Integer) row[1] == 1) {
                        return java.util.concurrent.CompletableFuture.completedFuture(new Object[]{row, 0});
                    }
                    return plugin.database().update("UPDATE sc_banknotes SET redeemed = 1, redeemed_by = ?, redeemed_at = ? WHERE id = ? AND redeemed = 0",
                            uuid.toString(), System.currentTimeMillis(), id).thenApply(changed -> new Object[]{row, changed});
                })
                .whenComplete((result, error) -> plugin.sync(() -> {
                    redeeming.remove(uuid);
                    Player online = Bukkit.getPlayer(uuid);
                    if (error != null) {
                        if (online != null) {
                            Inventories.giveOrDrop(online, single);
                            plugin.messages().send(online, "general.error");
                        }
                        return;
                    }
                    Object[] row = (Object[]) result[0];
                    int changed = (Integer) result[1];
                    if (row == null) {
                        if (online != null) {
                            Inventories.giveOrDrop(online, single);
                            plugin.messages().send(online, "banknote.invalid");
                        }
                        return;
                    }
                    double amount = (Double) row[0];
                    if (changed != 1) {
                        // Already redeemed: this item is a duplicate. Keep it confiscated and alert staff.
                        plugin.getLogger().warning("Duplicate banknote " + id + " (" + Money.format(amount) + ") used by " + player.getName());
                        String alert = plugin.messages().prefix() + plugin.messages().get("banknote.dupe-alert", "player", player.getName(),
                                "id", id, "amount", Money.format(amount));
                        for (Player staff : Bukkit.getOnlinePlayers()) {
                            if (staff.hasPermission("servercore.banknote.alerts")) {
                                staff.sendMessage(alert);
                            }
                        }
                        if (online != null) {
                            plugin.messages().send(online, "banknote.already-redeemed");
                        }
                        return;
                    }
                    if (!plugin.economy().deposit(Bukkit.getOfflinePlayer(uuid), amount)) {
                        plugin.getLogger().severe("Could not deposit banknote " + id + " for " + player.getName());
                        return;
                    }
                    plugin.transactions().log(TransactionType.NOTE_REDEEM, uuid, null, amount, "Note " + id);
                    if (online != null) {
                        plugin.messages().send(online, "banknote.redeemed", "amount", Money.format(amount));
                        Sounds.play(online, plugin.getConfig().getString("sounds.money-received", "ORB_PICKUP"));
                    }
                }));
    }

    public void lookup(org.bukkit.command.CommandSender sender, String id) {
        plugin.database().query("SELECT amount, creator, created, redeemed, redeemed_by, redeemed_at FROM sc_banknotes WHERE id = ?", rs -> {
            if (!rs.next()) {
                return null;
            }
            return new String[]{Money.format(rs.getDouble("amount")), rs.getString("creator"), TimeUtil.formatDate(rs.getLong("created")),
                    rs.getInt("redeemed") == 1 ? "yes" : "no", rs.getString("redeemed_by"),
                    rs.getLong("redeemed_at") > 0 ? TimeUtil.formatDate(rs.getLong("redeemed_at")) : "-"};
        }, id.toUpperCase()).thenAccept(row -> plugin.sync(() -> {
            if (row == null) {
                plugin.messages().send(sender, "banknote.lookup-missing", "id", id);
                return;
            }
            plugin.messages().sendRaw(sender, "banknote.lookup", "id", id.toUpperCase(), "amount", row[0], "creator", name(row[1]), "created", row[2],
                    "redeemed", row[3], "redeemer", row[4] == null ? "-" : name(row[4]), "redeemed_at", row[5]);
        }));
    }

    private static String name(String uuid) {
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName();
            return name == null ? uuid : name;
        } catch (IllegalArgumentException e) {
            return uuid;
        }
    }
}
