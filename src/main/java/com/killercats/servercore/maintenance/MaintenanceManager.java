package com.killercats.servercore.maintenance;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.util.Text;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maintenance mode: while enabled only operators, players with the bypass permission, members of
 * the configured ranks (via Vault permissions, e.g. LuckPerms/PermissionsEx) and whitelisted
 * players may join. State survives restarts (maintenance.yml).
 */
public final class MaintenanceManager {

    private final ServerCore plugin;
    private final File file;

    private boolean enabled;
    private String reason;
    private long startedAt;
    private long endsAt;
    private String enabledBy;
    private final Set<String> whitelist = new LinkedHashSet<String>();

    // schedule
    private long scheduledStart;
    private long scheduledDuration;
    private String scheduledReason;

    // config
    private final Set<String> allowedRanks = new LinkedHashSet<String>();
    private int kickDelaySeconds;
    private String defaultReason;
    private final Set<Integer> countdownMarks = new HashSet<Integer>();
    private boolean notifyStaff;

    private BukkitTask ticker;

    public MaintenanceManager(ServerCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "maintenance.yml");
        reload();
        loadState();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("maintenance");
        allowedRanks.clear();
        if (c != null) {
            for (String rank : c.getStringList("allowed-ranks")) {
                allowedRanks.add(rank.toLowerCase(Locale.ROOT));
            }
        }
        kickDelaySeconds = c == null ? 5 : Math.max(0, c.getInt("kick-delay-seconds", 5));
        defaultReason = c == null ? "Maintenance" : c.getString("default-reason", "Maintenance");
        countdownMarks.clear();
        if (c != null) {
            countdownMarks.addAll(c.getIntegerList("countdown-broadcasts"));
        }
        notifyStaff = c == null || c.getBoolean("notify-staff-on-attempt", true);
        if (!allowedRanks.isEmpty() && !plugin.vault().hasGroups()) {
            plugin.getLogger().warning("maintenance.allowed-ranks is set but no Vault permission plugin with group support was found."
                    + " Give staff the permission 'servercore.maintenance.bypass' instead.");
        }
    }

    private void loadState() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        enabled = data.getBoolean("enabled", false);
        reason = data.getString("reason", defaultReason);
        startedAt = data.getLong("started-at", 0);
        endsAt = data.getLong("ends-at", 0);
        enabledBy = data.getString("enabled-by", "Console");
        whitelist.clear();
        for (String name : data.getStringList("whitelist")) {
            whitelist.add(name.toLowerCase(Locale.ROOT));
        }
    }

    private void saveState() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("enabled", enabled);
        data.set("reason", reason);
        data.set("started-at", startedAt);
        data.set("ends-at", endsAt);
        data.set("enabled-by", enabledBy);
        data.set("whitelist", new ArrayList<String>(whitelist));
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save maintenance.yml: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
        }
        saveState();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (scheduledStart > 0) {
            long secondsLeft = (scheduledStart - now + 999) / 1000;
            if (secondsLeft <= 0) {
                long duration = scheduledDuration;
                String why = scheduledReason;
                cancelSchedule();
                enable(why, duration, "Scheduler");
            } else if (countdownMarks.contains((int) secondsLeft)) {
                broadcast("maintenance.countdown", "time", TimeUtil.formatDuration(secondsLeft * 1000), "reason", scheduledReason);
            }
        }
        if (enabled && endsAt > 0 && now >= endsAt) {
            disable("Timer");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String reason() {
        return reason;
    }

    public long endsAt() {
        return endsAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public String enabledBy() {
        return enabledBy;
    }

    public long scheduledStart() {
        return scheduledStart;
    }

    public String remainingText() {
        if (endsAt <= 0) {
            return plugin.messages().get("maintenance.unknown-end");
        }
        return TimeUtil.formatDuration(Math.max(0, endsAt - System.currentTimeMillis()));
    }

    public void enable(String newReason, long durationMillis, String actor) {
        enabled = true;
        reason = newReason == null || newReason.isEmpty() ? defaultReason : newReason;
        startedAt = System.currentTimeMillis();
        endsAt = durationMillis > 0 ? startedAt + durationMillis : 0;
        enabledBy = actor;
        saveState();
        broadcast("maintenance.enabled-broadcast", "reason", reason, "remaining", remainingText(), "actor", actor);
        plugin.getLogger().info("Maintenance mode enabled by " + actor + " (" + reason + ")");
        Bukkit.getScheduler().runTaskLater(plugin, this::kickDisallowed, kickDelaySeconds * 20L);
    }

    public void disable(String actor) {
        if (!enabled) {
            return;
        }
        enabled = false;
        endsAt = 0;
        saveState();
        broadcast("maintenance.disabled-broadcast", "actor", actor);
        plugin.getLogger().info("Maintenance mode disabled by " + actor);
    }

    public void schedule(long delayMillis, long durationMillis, String why) {
        scheduledStart = System.currentTimeMillis() + delayMillis;
        scheduledDuration = durationMillis;
        scheduledReason = why == null || why.isEmpty() ? defaultReason : why;
        broadcast("maintenance.scheduled-broadcast", "time", TimeUtil.formatDuration(delayMillis), "reason", scheduledReason);
    }

    public boolean cancelSchedule() {
        boolean had = scheduledStart > 0;
        scheduledStart = 0;
        scheduledDuration = 0;
        scheduledReason = null;
        return had;
    }

    public void setReason(String newReason) {
        reason = newReason;
        saveState();
    }

    public void extend(long millis) {
        long base = endsAt > 0 ? endsAt : System.currentTimeMillis();
        endsAt = base + millis;
        saveState();
    }

    private void kickDisallowed() {
        if (!enabled) {
            return;
        }
        String message = kickMessage();
        int kicked = 0;
        for (Player player : new ArrayList<Player>(Bukkit.getOnlinePlayers())) {
            if (!canJoin(player)) {
                player.kickPlayer(message);
                kicked++;
            }
        }
        if (kicked > 0) {
            plugin.getLogger().info("Maintenance: kicked " + kicked + " player(s).");
        }
    }

    public String kickMessage() {
        return plugin.messages().get("maintenance.kick-message", "reason", reason, "remaining", remainingText());
    }

    public boolean canJoin(Player player) {
        if (player.isOp()) {
            return true;
        }
        if (player.hasPermission("servercore.maintenance.bypass")) {
            return true;
        }
        if (whitelist.contains(player.getName().toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String rank : allowedRanks) {
            if (player.hasPermission("servercore.maintenance.rank." + rank) || plugin.vault().inGroup(player, rank)) {
                return true;
            }
        }
        return false;
    }

    public boolean addWhitelist(String name) {
        boolean added = whitelist.add(name.toLowerCase(Locale.ROOT));
        saveState();
        return added;
    }

    public boolean removeWhitelist(String name) {
        boolean removed = whitelist.remove(name.toLowerCase(Locale.ROOT));
        saveState();
        return removed;
    }

    public Set<String> whitelist() {
        return Collections.unmodifiableSet(whitelist);
    }

    public Set<String> allowedRanks() {
        return Collections.unmodifiableSet(allowedRanks);
    }

    public boolean notifyStaff() {
        return notifyStaff;
    }

    public List<String> motd() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("maintenance.motd");
        List<String> lines = new ArrayList<String>();
        if (c == null || !c.getBoolean("enabled", true)) {
            return lines;
        }
        lines.add(Text.color(Text.replace(c.getString("line1", ""), "reason", reason, "remaining", remainingText())));
        lines.add(Text.color(Text.replace(c.getString("line2", ""), "reason", reason, "remaining", remainingText())));
        return lines;
    }

    private void broadcast(String key, Object... placeholders) {
        String message = plugin.messages().get(key, placeholders);
        if (message.isEmpty()) {
            return;
        }
        for (String line : message.split("\n")) {
            Bukkit.broadcastMessage(plugin.messages().prefix() + line);
        }
    }
}
