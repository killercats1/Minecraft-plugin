package com.killercats.servercore.crates;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.crates.Crate.Reward;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Inventories;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Sounds;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crates with virtual keys. Keys live in the database (sc_crate_keys), so they can't be duplicated or
 * forged, and they can be given to offline players (vote rewards, store purchases). A crate is opened
 * from /crate or by right-clicking a block registered with /crate set.
 */
public final class CrateManager implements Listener {

    private final ServerCore plugin;
    private final Map<String, Crate> crates = new LinkedHashMap<String, Crate>();
    /** "world:x:y:z" -> crate id */
    private final Map<String, String> blocks = new ConcurrentHashMap<String, String>();
    private final Set<UUID> opening = new HashSet<UUID>();

    public CrateManager(ServerCore plugin) {
        this.plugin = plugin;
        reload();
        plugin.database().query("SELECT location, crate FROM sc_crate_blocks", rs -> {
            Map<String, String> map = new HashMap<String, String>();
            while (rs.next()) {
                map.put(rs.getString(1), rs.getString(2));
            }
            return map;
        }).thenAccept(blocks::putAll);
    }

    public void reload() {
        crates.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("crates.types");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(id);
            if (c == null) {
                continue;
            }
            Crate crate = Crate.load(id.toLowerCase(Locale.ROOT), c, plugin.getLogger());
            if (crate != null) {
                crates.put(crate.id, crate);
            }
        }
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("crates.enabled", true);
    }

    public Map<String, Crate> crates() {
        return Collections.unmodifiableMap(crates);
    }

    public Crate get(String id) {
        return id == null ? null : crates.get(id.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ keys

    /** UUID for a name without asking Mojang: online, then our records, then the offline-mode UUID. */
    public CompletableFuture<UUID> resolveUuid(String name) {
        return plugin.players().lookupUuid(name).thenApply(uuid -> {
            if (uuid != null) {
                return uuid;
            }
            if (Bukkit.getOnlineMode()) {
                return null;
            }
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        });
    }

    public CompletableFuture<Integer> keys(UUID uuid, String crate) {
        return plugin.database().query("SELECT amount FROM sc_crate_keys WHERE uuid = ? AND crate = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, uuid.toString(), crate);
    }

    public CompletableFuture<Map<String, Integer>> allKeys(UUID uuid) {
        return plugin.database().query("SELECT crate, amount FROM sc_crate_keys WHERE uuid = ?", rs -> {
            Map<String, Integer> map = new HashMap<String, Integer>();
            while (rs.next()) {
                map.put(rs.getString(1), rs.getInt(2));
            }
            return map;
        }, uuid.toString());
    }

    /** Adds (or with a negative amount removes) keys. Both statements are queued together, so it's safe to call often. */
    public CompletableFuture<Integer> addKeys(UUID uuid, String crate, int amount) {
        String insert = plugin.database().isMySql()
                ? "INSERT IGNORE INTO sc_crate_keys (uuid, crate, amount) VALUES (?, ?, 0)"
                : "INSERT OR IGNORE INTO sc_crate_keys (uuid, crate, amount) VALUES (?, ?, 0)";
        plugin.database().update(insert, uuid.toString(), crate);
        return plugin.database().update("UPDATE sc_crate_keys SET amount = CASE WHEN amount + ? < 0 THEN 0 ELSE amount + ? END"
                + " WHERE uuid = ? AND crate = ?", amount, amount, uuid.toString(), crate);
    }

    private CompletableFuture<Boolean> takeKey(UUID uuid, String crate) {
        return plugin.database().update("UPDATE sc_crate_keys SET amount = amount - 1 WHERE uuid = ? AND crate = ? AND amount > 0",
                uuid.toString(), crate).thenApply(rows -> rows != null && rows > 0);
    }

    // ------------------------------------------------------------------ opening

    /** Uses a key and opens the crate with the spin animation. Main thread. */
    public void open(Player player, Crate crate) {
        if (!opening.add(player.getUniqueId())) {
            return;
        }
        takeKey(player.getUniqueId(), crate.id).whenComplete((taken, error) -> plugin.sync(() -> {
            if (error != null || taken == null || !taken) {
                opening.remove(player.getUniqueId());
                plugin.messages().send(player, error != null ? "general.error" : "crates.no-key", "crate", crate.name);
                Sounds.play(player, "VILLAGER_NO");
                return;
            }
            Reward reward = crate.roll();
            if (!player.isOnline()) {
                // Logged out in the meantime: give the key back.
                opening.remove(player.getUniqueId());
                addKeys(player.getUniqueId(), crate.id, 1);
                return;
            }
            if (plugin.getConfig().getBoolean("crates.animation", true)) {
                new CrateSpinMenu(plugin, crate, reward, () -> finish(player, crate, reward)).start(player);
            } else {
                finish(player, crate, reward);
            }
        }));
    }

    /** Gives the reward. Called exactly once per opening. */
    void finish(Player player, Crate crate, Reward reward) {
        opening.remove(player.getUniqueId());
        if (reward.giveItem) {
            Inventories.giveOrDrop(player, reward.display.clone());
        }
        if (reward.money > 0) {
            if (plugin.economyReady() && plugin.economy().deposit(player, reward.money)) {
                plugin.transactions().log(TransactionType.CRATE, player.getUniqueId(), null, reward.money, Text.strip(crate.name));
            } else {
                plugin.getLogger().warning("Could not pay the crate money reward to " + player.getName() + " (economy unavailable).");
            }
        }
        for (String command : reward.commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            String line = Text.replace(command.trim(), "player", player.getName(), "uuid", player.getUniqueId(), "crate", crate.id);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
        plugin.messages().send(player, "crates.won", "reward", reward.displayName(), "crate", crate.name);
        Sounds.play(player, plugin.getConfig().getString("sounds.success", "LEVEL_UP"));
        if (reward.broadcast) {
            String message = plugin.messages().prefix() + plugin.messages().get("crates.broadcast", "player", player.getName(),
                    "reward", reward.displayName(), "crate", crate.name);
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(message);
            }
        }
        if (reward.money > 0 && !reward.giveItem && reward.commands.isEmpty()) {
            plugin.messages().send(player, "crates.money", "amount", Money.format(reward.money));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        opening.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ crate blocks

    static String key(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    public Crate crateAt(Block block) {
        return block == null ? null : get(blocks.get(key(block.getLocation())));
    }

    public void setBlock(Block block, Crate crate) {
        String key = key(block.getLocation());
        blocks.put(key, crate.id);
        plugin.database().update(plugin.database().upsert("sc_crate_blocks", "location", "crate"), key, crate.id);
    }

    public boolean removeBlock(Block block) {
        String key = key(block.getLocation());
        if (blocks.remove(key) == null) {
            return false;
        }
        plugin.database().update("DELETE FROM sc_crate_blocks WHERE location = ?", key);
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Crate crate = crateAt(event.getClickedBlock());
        if (crate == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!enabled() || !player.hasPermission("servercore.crate")) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            new CratePreviewMenu(plugin, crate).open(player);
        } else {
            open(player, crate);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (crateAt(event.getBlock()) != null) {
            event.setCancelled(true);
            if (event.getPlayer().hasPermission("servercore.crate.admin")) {
                plugin.messages().send(event.getPlayer(), "crates.break-hint");
            }
        }
    }
}
