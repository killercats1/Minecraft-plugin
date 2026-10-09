package com.killercats.servercore.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/** Optional PlaceholderAPI support for scoreboard/announcer lines, via reflection. */
public final class PlaceholderHook {

    private Method setPlaceholders;

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return false;
        }
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholders = api.getMethod("setPlaceholders", Player.class, String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String apply(Player player, String text) {
        if (setPlaceholders == null || text.indexOf('%') < 0) {
            return text;
        }
        try {
            return (String) setPlaceholders.invoke(null, player, text);
        } catch (Exception e) {
            return text;
        }
    }
}
