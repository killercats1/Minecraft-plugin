package com.killercats.servercore.data;

import com.killercats.servercore.ServerCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Caches {@link PlayerData} for online players. Data is loaded during the async pre-login so it
 * is ready before the player is in the world, and saved through the ordered database queue.
 */
public final class PlayerDataManager implements Listener {

    static final String[] COLUMNS = {"uuid", "name", "bank_balance", "bank_tier", "last_daily", "daily_streak",
            "accept_pay", "scoreboard", "payday_minutes", "last_seen"};

    public static final class TopEntry {
        public final String name;
        public final double amount;

        TopEntry(String name, double amount) {
            this.name = name;
            this.amount = amount;
        }
    }

    private final ServerCore plugin;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<UUID, PlayerData>();
    private final String upsertSql;

    public PlayerDataManager(ServerCore plugin) {
        this.plugin = plugin;
        this.upsertSql = plugin.database().upsert("sc_players", COLUMNS);
    }

    public CompletableFuture<PlayerData> load(UUID uuid, String fallbackName) {
        return plugin.database().query("SELECT * FROM sc_players WHERE uuid = ?", rs -> {
            PlayerData data = new PlayerData(uuid, fallbackName);
            if (rs.next()) {
                data.setName(rs.getString("name") == null ? fallbackName : rs.getString("name"));
                data.setBankBalance(rs.getDouble("bank_balance"));
                data.setBankTier(rs.getInt("bank_tier"));
                data.setLastDaily(rs.getLong("last_daily"));
                data.setDailyStreak(rs.getInt("daily_streak"));
                data.setAcceptPay(rs.getInt("accept_pay") == 1);
                data.setScoreboard(rs.getInt("scoreboard") == 1);
                data.setPaydayMinutes(rs.getInt("payday_minutes"));
                data.setLastSeen(rs.getLong("last_seen"));
                data.setDirty(false);
            } else {
                data.setDirty(true);
            }
            return data;
        }, uuid.toString());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            PlayerData data = load(event.getUniqueId(), event.getName()).get(10, TimeUnit.SECONDS);
            data.setName(event.getName());
            cache.put(event.getUniqueId(), data);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load data for " + event.getName() + " during login: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            cache.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!cache.containsKey(player.getUniqueId())) {
            // Pre-login load failed or timed out; use defaults now and merge the stored data when it arrives.
            cache.put(player.getUniqueId(), new PlayerData(player.getUniqueId(), player.getName()));
            load(player.getUniqueId(), player.getName()).thenAccept(data -> plugin.sync(() -> {
                if (player.isOnline()) {
                    cache.put(player.getUniqueId(), data);
                }
            }));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerData data = cache.remove(event.getPlayer().getUniqueId());
        if (data != null) {
            data.setLastSeen(System.currentTimeMillis());
            save(data);
        }
    }

    /** Cached data of an online player (never null for online players). */
    public PlayerData get(Player player) {
        PlayerData data = cache.get(player.getUniqueId());
        if (data == null) {
            data = new PlayerData(player.getUniqueId(), player.getName());
            cache.put(player.getUniqueId(), data);
        }
        return data;
    }

    public PlayerData getCached(UUID uuid) {
        return cache.get(uuid);
    }

    /**
     * Runs the action on the main thread with the player's data, loading it from storage when the
     * player is offline. Offline data passed to the action is saved afterwards automatically.
     */
    public void withData(UUID uuid, String name, Consumer<PlayerData> action) {
        PlayerData cached = cache.get(uuid);
        if (cached != null) {
            action.accept(cached);
            return;
        }
        load(uuid, name).thenAccept(data -> plugin.sync(() -> {
            PlayerData online = cache.get(uuid);
            PlayerData target = online != null ? online : data;
            action.accept(target);
            if (online == null && target.isDirty()) {
                save(target);
            }
        }));
    }

    public CompletableFuture<Integer> save(PlayerData data) {
        data.setDirty(false);
        return plugin.database().update(upsertSql, data.snapshot());
    }

    /** Queues a save for every changed online profile. */
    public void saveDirty() {
        for (PlayerData data : cache.values()) {
            if (data.isDirty()) {
                save(data);
            }
        }
    }

    public void saveAllBlocking() {
        List<CompletableFuture<Integer>> futures = new ArrayList<CompletableFuture<Integer>>();
        for (PlayerData data : cache.values()) {
            futures.add(save(data));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Saving player data on shutdown failed: " + e.getMessage());
        }
    }

    public Collection<PlayerData> online() {
        return cache.values();
    }

    /** Bank leaderboard. Dirty online data is flushed first; the ordered queue keeps results consistent. */
    public CompletableFuture<List<TopEntry>> topBank(int limit) {
        saveDirty();
        return plugin.database().query("SELECT name, bank_balance FROM sc_players WHERE bank_balance > 0 ORDER BY bank_balance DESC LIMIT " + limit, rs -> {
            List<TopEntry> list = new ArrayList<TopEntry>();
            while (rs.next()) {
                list.add(new TopEntry(rs.getString("name"), rs.getDouble("bank_balance")));
            }
            return list;
        });
    }

    public CompletableFuture<double[]> bankTotals() {
        saveDirty();
        return plugin.database().query("SELECT COUNT(*) AS accounts, COALESCE(SUM(bank_balance), 0) AS total FROM sc_players WHERE bank_balance > 0",
                rs -> rs.next() ? new double[]{rs.getDouble("accounts"), rs.getDouble("total")} : new double[]{0, 0});
    }

    /** Resolves an offline player's UUID from our own records (never triggers a Mojang lookup). */
    public CompletableFuture<UUID> lookupUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(online.getUniqueId());
        }
        return plugin.database().query("SELECT uuid FROM sc_players WHERE LOWER(name) = LOWER(?) ORDER BY last_seen DESC LIMIT 1",
                rs -> rs.next() ? UUID.fromString(rs.getString("uuid")) : null, name);
    }
}
