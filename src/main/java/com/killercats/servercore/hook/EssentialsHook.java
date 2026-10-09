package com.killercats.servercore.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Optional EssentialsX integration through reflection, so the plugin compiles and runs without
 * Essentials on the classpath. Used for AFK detection; balance-change logging lives in
 * {@link EssentialsBalanceListener}.
 */
public final class EssentialsHook {

    private Plugin essentials;
    private Method getUser;
    private Method isAfk;

    public boolean setup() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Essentials");
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        try {
            getUser = plugin.getClass().getMethod("getUser", Player.class);
            essentials = plugin;
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public boolean isPresent() {
        return essentials != null;
    }

    public String version() {
        return essentials == null ? "none" : essentials.getDescription().getVersion();
    }

    public boolean isAfk(Player player) {
        if (essentials == null) {
            return false;
        }
        try {
            Object user = getUser.invoke(essentials, player);
            if (user == null) {
                return false;
            }
            if (isAfk == null) {
                isAfk = user.getClass().getMethod("isAfk");
            }
            return (Boolean) isAfk.invoke(user);
        } catch (Exception e) {
            return false;
        }
    }
}
