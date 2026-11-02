package com.killercats.servercore.security;

import com.killercats.servercore.ServerCore;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two safety nets:
 * <ul>
 *     <li><b>Login check:</b> an offline-mode (Eaglercraft) server without a login plugin lets anyone
 *     join with the owner's name. If no login plugin is running, staff accounts are locked (mode
 *     "protect") or staff are warned (mode "warn").</li>
 *     <li><b>Recommended plugins:</b> {@code /servercore check} lists which of the plugins this setup is
 *     built around are installed, and the console gets the same list at startup.</li>
 * </ul>
 */
public final class SecurityCheck implements Listener {

    /** Bukkit plugin names of login/auth plugins. */
    private static final String[] LOGIN_PLUGINS = {"LoginSecurity", "AuthMe", "AuthMeReloaded", "nLogin", "OpeNLogin",
            "JPremium", "FastLogin", "UserLogin", "xAuth", "LibreLogin", "EaglercraftXServer", "EaglerXServer", "EaglercraftXBukkit"};

    private final ServerCore plugin;

    public SecurityCheck(ServerCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection config() {
        return plugin.getConfig().getConfigurationSection("security.login-check");
    }

    private String mode() {
        ConfigurationSection c = config();
        String mode = c == null ? "protect" : c.getString("mode", "protect");
        return mode == null ? "protect" : mode.toLowerCase(Locale.ROOT);
    }

    /** Name of the running login plugin, "online-mode", "proxy" or null when the server is unprotected. */
    public String loginProtection() {
        if (Bukkit.getOnlineMode()) {
            return "online-mode";
        }
        ConfigurationSection c = config();
        if (c != null && c.getBoolean("auth-on-proxy", false)) {
            return "proxy";
        }
        for (String name : LOGIN_PLUGINS) {
            Plugin found = Bukkit.getPluginManager().getPlugin(name);
            if (found != null && found.isEnabled()) {
                return found.getName();
            }
        }
        if (c != null) {
            for (String name : c.getStringList("extra-login-plugins")) {
                Plugin found = Bukkit.getPluginManager().getPlugin(name);
                if (found != null && found.isEnabled()) {
                    return found.getName();
                }
            }
        }
        return null;
    }

    private boolean unprotected() {
        return !"off".equals(mode()) && loginProtection() == null;
    }

    private static boolean isStaff(Player player) {
        return player.isOp() || player.hasPermission("servercore.staff") || player.hasPermission("servercore.maintenance.bypass");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED || !"protect".equals(mode()) || !unprotected()) {
            return;
        }
        Player player = event.getPlayer();
        if (isStaff(player)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, plugin.messages().get("security.staff-locked"));
            plugin.getLogger().warning("Blocked " + player.getName() + " (staff) from joining: no login plugin is running, "
                    + "so anyone could be using that name. Install LoginSecurity or AuthMe, or set security.login-check.mode.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if ("warn".equals(mode()) && isStaff(player) && unprotected()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.messages().send(player, "security.staff-warning");
                }
            }, 60L);
        }
    }

    /** Logs the startup report. Call one tick after enable, when every plugin has loaded. */
    public void logStartupReport() {
        String login = loginProtection();
        if (login == null && !"off".equals(mode())) {
            plugin.getLogger().severe("==============================================================");
            plugin.getLogger().severe(" No login plugin found! This server is in offline mode, so anyone");
            plugin.getLogger().severe(" can join with any name, including yours.");
            plugin.getLogger().severe(" Install LoginSecurity (or AuthMe). Until then staff accounts are "
                    + ("protect".equals(mode()) ? "LOCKED." : "only warned."));
            plugin.getLogger().severe(" (If players log in on the proxy, set security.login-check.auth-on-proxy: true)");
            plugin.getLogger().severe("==============================================================");
        }
        List<String> missing = new ArrayList<String>();
        for (Map.Entry<String, String[]> entry : recommended().entrySet()) {
            if (find(entry.getValue()) == null) {
                missing.add(entry.getKey());
            }
        }
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("Recommended plugins not installed: " + String.join(", ", missing) + ". Run /servercore check for details.");
        }
    }

    /** /servercore check */
    public void report(CommandSender sender) {
        plugin.messages().sendRaw(sender, "security.check-header");
        String login = loginProtection();
        if (login != null) {
            plugin.messages().sendRaw(sender, "security.check-login-ok", "plugin", login);
        } else {
            plugin.messages().sendRaw(sender, "security.check-login-missing", "mode", mode());
        }
        for (Map.Entry<String, String[]> entry : recommended().entrySet()) {
            Plugin found = find(entry.getValue());
            if (found != null) {
                plugin.messages().sendRaw(sender, "security.check-ok", "plugin", entry.getKey(),
                        "version", found.getDescription().getVersion());
            } else {
                plugin.messages().sendRaw(sender, "security.check-missing", "plugin", entry.getKey(),
                        "why", plugin.messages().get("security.why." + entry.getKey().toLowerCase(Locale.ROOT).replace(" ", "-")));
            }
        }
    }

    private static Plugin find(String[] names) {
        for (String name : names) {
            Plugin found = Bukkit.getPluginManager().getPlugin(name);
            if (found != null && found.isEnabled()) {
                return found;
            }
        }
        return null;
    }

    /** Display name -> accepted Bukkit plugin names. */
    private static Map<String, String[]> recommended() {
        Map<String, String[]> map = new LinkedHashMap<String, String[]>();
        map.put("Vault", new String[]{"Vault"});
        map.put("EssentialsX", new String[]{"Essentials"});
        map.put("LuckPerms", new String[]{"LuckPerms"});
        map.put("GriefPrevention", new String[]{"GriefPrevention"});
        map.put("CoreProtect", new String[]{"CoreProtect"});
        map.put("NoCheatPlus", new String[]{"NoCheatPlus"});
        map.put("NuVotifier", new String[]{"Votifier", "NuVotifier"});
        map.put("DiscordSRV", new String[]{"DiscordSRV"});
        map.put("Tebex", new String[]{"Tebex", "BuycraftX", "Buycraft"});
        return map;
    }
}
