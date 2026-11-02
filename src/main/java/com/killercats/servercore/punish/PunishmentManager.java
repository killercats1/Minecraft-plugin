package com.killercats.servercore.punish;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.punish.Punishment.Type;
import com.killercats.servercore.util.DiscordWebhook;
import com.killercats.servercore.util.Text;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Warnings, kicks, mutes and bans with a full history in sc_punishments, ban-evasion (alt) detection
 * through the IPs players join from, and an optional Discord log. Bans are checked before the player
 * joins; mutes are cached in memory while the player is online.
 */
public final class PunishmentManager implements Listener {

    /** A preset reason with escalating steps: the 1st offence uses step 1, the 2nd step 2, and so on. */
    public static final class Preset {
        public final String id;
        public final String name;
        public final String reason;
        public final Material icon;
        public final List<Step> steps;

        Preset(String id, String name, String reason, Material icon, List<Step> steps) {
            this.id = id;
            this.name = name;
            this.reason = reason;
            this.icon = icon;
            this.steps = steps;
        }

        public Step step(int previousOffences) {
            return steps.get(Math.min(previousOffences, steps.size() - 1));
        }
    }

    public static final class Step {
        public final Type type;
        /** 0 = permanent (for bans and mutes). */
        public final long duration;

        Step(Type type, long duration) {
            this.type = type;
            this.duration = duration;
        }

        public String describe() {
            switch (type) {
                case BAN:
                    return duration > 0 ? "Tempban " + TimeUtil.formatDuration(duration) : "Permanent ban";
                case MUTE:
                    return duration > 0 ? "Mute " + TimeUtil.formatDuration(duration) : "Permanent mute";
                case KICK:
                    return "Kick";
                default:
                    return "Warning";
            }
        }

        public String permission() {
            switch (type) {
                case BAN:
                    return duration > 0 ? "servercore.punish.tempban" : "servercore.punish.ban";
                case MUTE:
                    return "servercore.punish.mute";
                default:
                    return "servercore.punish";
            }
        }

        static Step parse(String text) {
            String[] parts = text.trim().split("\\s+");
            Type type = Type.parse(parts[0]);
            if (type == null) {
                return null;
            }
            long duration = 0;
            if (parts.length > 1 && (type == Type.BAN || type == Type.MUTE)) {
                duration = Math.max(0, TimeUtil.parseDuration(parts[1]));
            }
            return new Step(type, duration);
        }
    }

    private final ServerCore plugin;
    private final Map<UUID, Punishment> mutes = new ConcurrentHashMap<UUID, Punishment>();
    private final Map<UUID, List<String>> evasionAlerts = new ConcurrentHashMap<UUID, List<String>>();
    private final Map<String, Preset> presets = new LinkedHashMap<String, Preset>();
    private Set<String> mutedCommands = Collections.emptySet();

    public PunishmentManager(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        presets.clear();
        ConfigurationSection section = config().getConfigurationSection("presets");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection p = section.getConfigurationSection(id);
                if (p == null) {
                    continue;
                }
                List<Step> steps = new ArrayList<Step>();
                for (String raw : p.getStringList("steps")) {
                    Step step = Step.parse(raw);
                    if (step == null) {
                        plugin.getLogger().warning("Punishment preset '" + id + "' has an unknown step '" + raw + "'.");
                    } else {
                        steps.add(step);
                    }
                }
                if (steps.isEmpty()) {
                    continue;
                }
                Material icon = Material.matchMaterial(p.getString("icon", "PAPER"));
                presets.put(id.toLowerCase(Locale.ROOT), new Preset(id.toLowerCase(Locale.ROOT), p.getString("name", id),
                        p.getString("reason", id), icon == null ? Material.PAPER : icon, steps));
            }
        }
        Set<String> commands = new HashSet<String>();
        for (String command : config().getStringList("muted-blocked-commands")) {
            commands.add(command.toLowerCase(Locale.ROOT));
        }
        mutedCommands = commands;
    }

    private ConfigurationSection config() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("punishments");
        return section != null ? section : plugin.getConfig().createSection("punishments");
    }

    public boolean enabled() {
        return config().getBoolean("enabled", true);
    }

    public Map<String, Preset> presets() {
        return presets;
    }

    // ------------------------------------------------------------------ joining

    /** Public IP as text, or null for local/proxy addresses (they are shared by everyone behind a proxy). */
    static String realIp(InetAddress address) {
        if (address == null || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()) {
            return null;
        }
        return address.getHostAddress();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!enabled() || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        String ip = realIp(event.getAddress());
        long now = System.currentTimeMillis();
        try {
            List<Punishment> active = plugin.database().query("SELECT * FROM sc_punishments WHERE uuid = ? AND active = 1",
                    rs -> {
                        List<Punishment> list = new ArrayList<Punishment>();
                        while (rs.next()) {
                            list.add(Punishment.read(rs));
                        }
                        return list;
                    }, uuid.toString()).get(5, TimeUnit.SECONDS);
            Punishment mute = null;
            for (Punishment p : active) {
                if (!p.inForce(now)) {
                    // Expired: close it so it stops showing as active.
                    plugin.database().update("UPDATE sc_punishments SET active = 0 WHERE id = ?", p.id);
                } else if (p.type == Type.BAN) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(p));
                    return;
                } else if (p.type == Type.MUTE) {
                    mute = p;
                }
            }
            if (ip != null) {
                plugin.database().update(plugin.database().upsert("sc_player_ips", "uuid", "ip", "name", "last_seen"),
                        uuid.toString(), ip, name, now);
                String evasion = config().getString("ban-evasion", "alert").toLowerCase(Locale.ROOT);
                if (!evasion.equals("off")) {
                    List<String> banned = plugin.database().query("SELECT DISTINCT p.name FROM sc_punishments p WHERE p.type = 'BAN'"
                                    + " AND p.active = 1 AND (p.expires = 0 OR p.expires > ?) AND p.uuid <> ?"
                                    + " AND (p.ip = ? OR p.uuid IN (SELECT i.uuid FROM sc_player_ips i WHERE i.ip = ?))",
                            rs -> {
                                List<String> list = new ArrayList<String>();
                                while (rs.next()) {
                                    list.add(rs.getString(1));
                                }
                                return list;
                            }, now, uuid.toString(), ip, ip).get(5, TimeUnit.SECONDS);
                    if (!banned.isEmpty()) {
                        if (evasion.equals("block")) {
                            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.messages().get("punish.evasion-screen",
                                    "accounts", String.join(", ", banned)));
                            notifyStaffLater("punish.evasion-blocked", "player", name, "accounts", String.join(", ", banned));
                            return;
                        }
                        evasionAlerts.put(uuid, banned);
                    }
                }
            }
            if (mute != null) {
                mutes.put(uuid, mute);
            } else {
                mutes.remove(uuid);
            }
        } catch (Exception e) {
            // Never lock everyone out because the database hiccuped.
            plugin.getLogger().log(Level.WARNING, "Could not check punishments for " + name + "; letting them join.", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        List<String> accounts = evasionAlerts.remove(event.getPlayer().getUniqueId());
        if (accounts != null) {
            String list = String.join(", ", accounts);
            notifyStaff("punish.evasion-alert", "player", event.getPlayer().getName(), "accounts", list);
            discord(":warning: **" + event.getPlayer().getName() + "** joined from the same IP as banned account(s): " + list);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        mutes.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ mutes

    private Punishment activeMute(Player player) {
        Punishment mute = mutes.get(player.getUniqueId());
        if (mute == null) {
            return null;
        }
        if (!mute.inForce(System.currentTimeMillis())) {
            mutes.remove(player.getUniqueId());
            plugin.database().update("UPDATE sc_punishments SET active = 0 WHERE id = ?", mute.id);
            return null;
        }
        return mute;
    }

    private void tellMuted(Player player, Punishment mute) {
        plugin.messages().send(player, "punish.you-are-muted", "reason", mute.reason,
                "time", mute.permanent() ? plugin.messages().get("punish.permanent") : TimeUtil.formatDuration(mute.expires - System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Punishment mute = activeMute(event.getPlayer());
        if (mute != null) {
            event.setCancelled(true);
            tellMuted(event.getPlayer(), mute);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        int space = raw.indexOf(' ');
        String label = (space < 0 ? raw.substring(1) : raw.substring(1, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (!mutedCommands.contains(label)) {
            return;
        }
        Punishment mute = activeMute(event.getPlayer());
        if (mute != null) {
            event.setCancelled(true);
            tellMuted(event.getPlayer(), mute);
        }
    }

    // ------------------------------------------------------------------ lookups

    /** Resolved target: uuid, exact name and last known public IP (may be null). */
    public static final class Target {
        public final UUID uuid;
        public final String name;
        public final String ip;

        Target(UUID uuid, String name, String ip) {
            this.uuid = uuid;
            this.name = name;
            this.ip = ip;
        }
    }

    /** Finds a player by name: online first, then anyone who has ever joined. Completes with null if unknown. */
    public CompletableFuture<Target> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            Target target = new Target(online.getUniqueId(), online.getName(),
                    realIp(online.getAddress() == null ? null : online.getAddress().getAddress()));
            return CompletableFuture.completedFuture(target);
        }
        return plugin.database().query("SELECT uuid, name FROM sc_players WHERE LOWER(name) = LOWER(?) ORDER BY last_seen DESC LIMIT 1",
                rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null, name).thenCompose(found -> {
            if (found == null) {
                // Not in sc_players (e.g. joined before ServerCore): try earlier punishments.
                return plugin.database().query("SELECT uuid, name FROM sc_punishments WHERE LOWER(name) = LOWER(?) ORDER BY id DESC LIMIT 1",
                        rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null, name);
            }
            return CompletableFuture.completedFuture(found);
        }).thenCompose(found -> {
            if (found == null) {
                return CompletableFuture.completedFuture(null);
            }
            return plugin.database().query("SELECT ip FROM sc_player_ips WHERE uuid = ? ORDER BY last_seen DESC LIMIT 1",
                    rs -> new Target(UUID.fromString(found[0]), found[1] == null ? name : found[1], rs.next() ? rs.getString(1) : null),
                    found[0]);
        });
    }

    public CompletableFuture<Integer> offences(UUID uuid, String preset) {
        return plugin.database().query("SELECT COUNT(*) FROM sc_punishments WHERE uuid = ? AND preset = ? AND removed_by IS NULL",
                rs -> rs.next() ? rs.getInt(1) : 0, uuid.toString(), preset);
    }

    public CompletableFuture<List<Punishment>> history(UUID uuid) {
        return plugin.database().query("SELECT * FROM sc_punishments WHERE uuid = ? ORDER BY id DESC", rs -> {
            List<Punishment> list = new ArrayList<Punishment>();
            while (rs.next()) {
                list.add(Punishment.read(rs));
            }
            return list;
        }, uuid.toString());
    }

    /** Other accounts that joined from one of this player's IPs: {name, "1" if banned right now}. */
    public CompletableFuture<List<String[]>> alts(UUID uuid) {
        return plugin.database().query("SELECT DISTINCT b.uuid, b.name, (SELECT COUNT(*) FROM sc_punishments p WHERE p.uuid = b.uuid"
                        + " AND p.type = 'BAN' AND p.active = 1 AND (p.expires = 0 OR p.expires > ?)) AS banned"
                        + " FROM sc_player_ips a JOIN sc_player_ips b ON a.ip = b.ip WHERE a.uuid = ? AND b.uuid <> ?",
                rs -> {
                    Map<String, String[]> byUuid = new LinkedHashMap<String, String[]>();
                    while (rs.next()) {
                        byUuid.put(rs.getString(1), new String[]{rs.getString(2), rs.getInt(3) > 0 ? "1" : "0"});
                    }
                    return new ArrayList<String[]>(byUuid.values());
                }, System.currentTimeMillis(), uuid.toString(), uuid.toString());
    }

    // ------------------------------------------------------------------ punishing

    private static String staffName(CommandSender staff) {
        return staff instanceof Player ? staff.getName() : "Console";
    }

    /**
     * Punishes a player. Runs the lookups in the background and reports back to the staff member.
     *
     * @param duration 0 = permanent (bans and mutes only)
     * @param preset   preset id, or null for a manual punishment
     */
    public void punish(CommandSender staff, String targetName, Type type, long duration, String reason, String preset) {
        resolve(targetName).whenComplete((target, error) -> plugin.sync(() -> {
            if (error != null) {
                plugin.messages().send(staff, "general.error");
                return;
            }
            if (target == null) {
                plugin.messages().send(staff, "general.player-not-found", "player", targetName);
                return;
            }
            apply(staff, target, type, duration, reason, preset);
        }));
    }

    /** Applies a punishment to an already resolved player. Main thread. */
    public void apply(CommandSender staff, Target target, Type type, long duration, String reason, String preset) {
        Player online = Bukkit.getPlayer(target.uuid);
        if (staff instanceof Player && ((Player) staff).getUniqueId().equals(target.uuid)) {
            plugin.messages().send(staff, "punish.not-yourself");
            return;
        }
        if (online != null && staff instanceof Player && online.hasPermission("servercore.punish.exempt")) {
            plugin.messages().send(staff, "punish.exempt", "player", target.name);
            return;
        }
        if (type == Type.KICK && online == null) {
            plugin.messages().send(staff, "punish.not-online", "player", target.name);
            return;
        }
        long now = System.currentTimeMillis();
        long expires = (type == Type.BAN || type == Type.MUTE) && duration > 0 ? now + duration : 0;
        boolean enforced = type == Type.BAN || type == Type.MUTE;
        String staffName = staffName(staff);
        String staffUuid = staff instanceof Player ? ((Player) staff).getUniqueId().toString() : null;
        String cleanReason = reason == null || reason.trim().isEmpty() ? plugin.messages().get("punish.no-reason") : Text.strip(reason.trim());
        if (cleanReason.length() > 200) {
            cleanReason = cleanReason.substring(0, 200);
        }
        if (enforced) {
            // A new ban/mute replaces the old one, so there is only ever one in force.
            plugin.database().update("UPDATE sc_punishments SET active = 0 WHERE uuid = ? AND type = ? AND active = 1",
                    target.uuid.toString(), type.name());
        }
        final String finalReason = cleanReason;
        plugin.database().update("INSERT INTO sc_punishments (type, uuid, name, ip, reason, preset, staff, staff_name, created, expires, active)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                type.name(), target.uuid.toString(), target.name, target.ip, cleanReason, preset, staffUuid, staffName, now, expires,
                enforced ? 1 : 0);
        plugin.database().query("SELECT id FROM sc_punishments WHERE uuid = ? AND created = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : 0L, target.uuid.toString(), now).whenComplete((id, error) -> plugin.sync(() -> {
            long pid = id == null ? 0 : id;
            Punishment p = new Punishment(pid, type, target.uuid, target.name, target.ip, finalReason, preset, staffName, now, expires,
                    enforced, null);
            enforce(p);
            announce(staff, p, duration);
        }));
    }

    private void enforce(Punishment p) {
        Player online = Bukkit.getPlayer(p.uuid);
        switch (p.type) {
            case BAN:
                if (online != null) {
                    online.kickPlayer(banScreen(p));
                }
                break;
            case KICK:
                if (online != null) {
                    online.kickPlayer(plugin.messages().get("punish.kick-screen", "reason", p.reason, "staff", p.staffName));
                }
                break;
            case MUTE:
                if (online != null) {
                    mutes.put(p.uuid, p);
                    tellMuted(online, p);
                }
                break;
            default:
                if (online != null) {
                    plugin.messages().send(online, "punish.you-were-warned", "reason", p.reason, "staff", p.staffName);
                }
        }
    }

    private void announce(CommandSender staff, Punishment p, long duration) {
        String what = describe(p.type, duration);
        notifyStaff("punish.staff-notice", "player", p.name, "action", what, "staff", p.staffName, "reason", p.reason, "id", p.id);
        if (!(staff instanceof Player) || !staff.hasPermission("servercore.punish.notify")) {
            plugin.messages().send(staff, "punish.staff-notice", "player", p.name, "action", what, "staff", p.staffName, "reason", p.reason, "id", p.id);
        }
        if ((p.type == Type.BAN || p.type == Type.KICK) && config().getBoolean("broadcast-bans", true)) {
            String message = plugin.messages().prefix() + plugin.messages().get("punish.public-broadcast", "player", p.name, "action", what);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.hasPermission("servercore.punish.notify")) {
                    online.sendMessage(message);
                }
            }
        }
        discord(":hammer: **" + what + "**: " + p.name + " by " + p.staffName + " - " + p.reason + " (#" + p.id + ")");
        plugin.getLogger().info(p.staffName + " -> " + what + " " + p.name + ": " + p.reason);
    }

    public String describe(Type type, long duration) {
        return new Step(type, type == Type.BAN || type == Type.MUTE ? duration : 0).describe();
    }

    /** Lifts an active ban or mute. */
    public void pardon(CommandSender staff, String targetName, Type type, String note) {
        resolve(targetName).whenComplete((target, error) -> plugin.sync(() -> {
            if (error != null || target == null) {
                plugin.messages().send(staff, error != null ? "general.error" : "general.player-not-found", "player", targetName);
                return;
            }
            String staffName = staffName(staff);
            plugin.database().update("UPDATE sc_punishments SET active = 0, removed_by = ?, removed_at = ? WHERE uuid = ? AND type = ? AND active = 1",
                    staffName, System.currentTimeMillis(), target.uuid.toString(), type.name()).whenComplete((rows, err) -> plugin.sync(() -> {
                if (err != null) {
                    plugin.messages().send(staff, "general.error");
                    return;
                }
                if (rows == null || rows == 0) {
                    plugin.messages().send(staff, "punish.nothing-to-remove", "player", target.name, "type", type.name().toLowerCase(Locale.ROOT));
                    return;
                }
                String what = type == Type.BAN ? "Unban" : "Unmute";
                if (type == Type.MUTE) {
                    mutes.remove(target.uuid);
                    Player online = Bukkit.getPlayer(target.uuid);
                    if (online != null) {
                        plugin.messages().send(online, "punish.unmuted");
                    }
                }
                String reason = note == null || note.isEmpty() ? "-" : note;
                notifyStaff("punish.removed", "player", target.name, "action", what, "staff", staffName, "reason", reason);
                if (!(staff instanceof Player) || !staff.hasPermission("servercore.punish.notify")) {
                    plugin.messages().send(staff, "punish.removed", "player", target.name, "action", what, "staff", staffName, "reason", reason);
                }
                discord(":unlock: **" + what + "**: " + target.name + " by " + staffName + (note == null || note.isEmpty() ? "" : " - " + note));
            }));
        }));
    }

    // ------------------------------------------------------------------ output helpers

    public String banScreen(Punishment p) {
        String time = p.permanent() ? plugin.messages().get("punish.permanent") : TimeUtil.formatDuration(p.expires - System.currentTimeMillis());
        return plugin.messages().get("punish.ban-screen", "reason", p.reason, "staff", p.staffName, "time", time, "id", p.id,
                "appeal", config().getString("appeal", ""));
    }

    private void notifyStaff(String key, Object... placeholders) {
        String message = plugin.messages().prefix() + plugin.messages().get(key, placeholders);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("servercore.punish.notify")) {
                online.sendMessage(message);
            }
        }
    }

    private void notifyStaffLater(String key, Object... placeholders) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> notifyStaff(key, placeholders));
        }
    }

    private void discord(String text) {
        String url = config().getString("discord-webhook-url", "");
        if (url == null || url.trim().isEmpty() || !plugin.isEnabled()) {
            return;
        }
        String webhook = url.trim();
        if (!DiscordWebhook.isValidUrl(webhook)) {
            plugin.getLogger().warning("punishments.discord-webhook-url is not a Discord webhook URL.");
            return;
        }
        String clean = Text.strip(text);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                DiscordWebhook.send(webhook, clean);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not send the punishment log to Discord: " + e.getMessage());
            }
        });
    }
}
