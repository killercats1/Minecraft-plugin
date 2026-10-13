package com.killercats.servercore.vote;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Sounds;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Vote rewards. Server lists send votes through Votifier/NuVotifier ({@link VotifierHook}); every
 * vote is stored in sc_votes. A player who is online is rewarded straight away, otherwise the vote
 * waits (delivered = 0) until they next join. Rows are keyed by lower-case name because vote sites
 * only know the name typed on the site, and the player may never have joined yet.
 */
public final class VoteManager implements Listener {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final int PENDING = 0;
    private static final int DELIVERED = 1;
    private static final int SKIPPED = 2;

    private final ServerCore plugin;

    public VoteManager(ServerCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection config() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("votes");
        return section != null ? section : plugin.getConfig().createSection("votes");
    }

    public boolean enabled() {
        return config().getBoolean("enabled", true);
    }

    /** Vote links as name/url pairs, in config order. */
    public List<String[]> links() {
        List<String[]> out = new ArrayList<String[]>();
        for (java.util.Map<?, ?> entry : config().getMapList("links")) {
            Object url = entry.get("url");
            if (url == null || url.toString().trim().isEmpty()) {
                continue;
            }
            Object name = entry.get("name");
            out.add(new String[]{name == null ? url.toString() : name.toString(), url.toString().trim()});
        }
        return out;
    }

    /** Called on the main thread for every vote Votifier receives (and by /vote test). */
    public void handleVote(String username, String service) {
        if (!enabled()) {
            return;
        }
        String name = username == null ? "" : username.trim();
        String site = service == null || service.trim().isEmpty() ? "unknown" : service.trim();
        if (!NAME.matcher(name).matches()) {
            plugin.getLogger().warning("Ignored a vote from " + site + " for invalid username '" + name + "'.");
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        boolean rewardNow = online != null;
        int state = rewardNow ? DELIVERED : config().getBoolean("offline-rewards", true) ? PENDING : SKIPPED;
        String stored = online != null ? online.getName() : name;
        plugin.getLogger().info("Vote received from " + site + " for " + stored + (rewardNow ? "." : " (offline, reward waits until they join)."));
        plugin.database().update("INSERT INTO sc_votes (name_lower, name, service, created, delivered) VALUES (?, ?, ?, ?, ?)",
                stored.toLowerCase(Locale.ROOT), stored, cut(site, 64), System.currentTimeMillis(), state)
                .whenComplete((rows, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("Could not save a vote for " + stored + ": " + error.getMessage());
                    }
                });
        if (rewardNow) {
            reward(online, site);
        }
        if (config().getBoolean("broadcast", true)) {
            String message = plugin.messages().prefix() + plugin.messages().get("vote.broadcast", "player", stored, "service", site);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player != online) {
                    player.sendMessage(message);
                }
            }
        }
    }

    private void reward(Player player, String site) {
        double money = Money.round(config().getDouble("money", 250));
        if (money > 0) {
            if (plugin.economyReady() && plugin.economy().deposit(player, money)) {
                plugin.transactions().log(TransactionType.VOTE, player.getUniqueId(), null, money, "Voted on " + cut(site, 200));
            } else {
                plugin.getLogger().warning("Could not pay the vote reward to " + player.getName() + " (economy unavailable).");
                money = 0;
            }
        }
        for (String command : config().getStringList("commands")) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            String line = Text.replace(command.trim(), "player", player.getName(), "uuid", player.getUniqueId(), "service", site);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
        plugin.messages().send(player, money > 0 ? "vote.received" : "vote.received-no-money", "service", site, "amount", Money.format(money));
        Sounds.play(player, plugin.getConfig().getString("sounds.success", "LEVEL_UP"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String key = player.getName().toLowerCase(Locale.ROOT);
        plugin.database().query("SELECT id, service, delivered, created FROM sc_votes WHERE name_lower = ?", rs -> {
            List<Object[]> pending = new ArrayList<Object[]>();
            long last = 0;
            while (rs.next()) {
                last = Math.max(last, rs.getLong("created"));
                if (rs.getInt("delivered") == PENDING) {
                    pending.add(new Object[]{rs.getLong("id"), rs.getString("service")});
                }
            }
            return new Object[]{pending, last};
        }, key).whenComplete((result, error) -> plugin.sync(() -> {
            if (error != null) {
                plugin.getLogger().warning("Could not load votes for " + player.getName() + ": " + error.getMessage());
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object[]> pending = (List<Object[]>) result[0];
            long last = (Long) result[1];
            for (Object[] vote : pending) {
                claimPending(uuid, (Long) vote[0], (String) vote[1]);
            }
            if (pending.isEmpty() && config().getBoolean("remind-on-join", true) && System.currentTimeMillis() - last >= DAY
                    && player.hasPermission("servercore.vote") && !links().isEmpty()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        plugin.messages().send(player, "vote.reminder");
                    }
                }, 80L);
            }
        }));
    }

    /** Marks one stored vote delivered and rewards it; the conditional update stops a vote paying twice. */
    private void claimPending(UUID uuid, long id, String site) {
        plugin.database().update("UPDATE sc_votes SET delivered = ? WHERE id = ? AND delivered = ?", DELIVERED, id, PENDING)
                .whenComplete((rows, error) -> plugin.sync(() -> {
                    if (error != null || rows == null || rows != 1) {
                        return;
                    }
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null) {
                        // Left before it was paid: put it back for next time.
                        plugin.database().update("UPDATE sc_votes SET delivered = ? WHERE id = ?", PENDING, id);
                        return;
                    }
                    reward(player, site);
                }));
    }

    public CompletableFuture<Integer> totalVotes(String name) {
        return plugin.database().query("SELECT COUNT(*) FROM sc_votes WHERE name_lower = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, name.toLowerCase(Locale.ROOT));
    }

    /** Top voters this calendar month as name/count pairs. */
    public CompletableFuture<List<Object[]>> topThisMonth(int limit) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.DAY_OF_MONTH, 1);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        return plugin.database().query("SELECT MAX(name) AS name, COUNT(*) AS votes FROM sc_votes WHERE created >= ?"
                + " GROUP BY name_lower ORDER BY votes DESC LIMIT " + limit, rs -> {
            List<Object[]> out = new ArrayList<Object[]>();
            while (rs.next()) {
                out.add(new Object[]{rs.getString("name"), rs.getInt("votes")});
            }
            return out;
        }, start.getTimeInMillis());
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
