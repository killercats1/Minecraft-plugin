package com.killercats.servercore.lag;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;

/**
 * Server TPS (1, 5 and 15 minute averages). Reads Spigot's own MinecraftServer.recentTps through
 * reflection; if that isn't available, measures tick speed itself.
 */
public final class TpsMeter {

    private Object server;
    private Field recentTps;
    private BukkitTask fallbackTask;
    private final double[] fallback = {20, 20, 20};
    private long lastSample;

    public TpsMeter(Plugin plugin) {
        try {
            String craft = Bukkit.getServer().getClass().getPackage().getName();
            String version = craft.substring(craft.lastIndexOf('.') + 1);
            Class<?> minecraftServer = Class.forName("net.minecraft.server." + version + ".MinecraftServer");
            server = minecraftServer.getMethod("getServer").invoke(null);
            recentTps = minecraftServer.getField("recentTps");
            if (!(recentTps.get(server) instanceof double[])) {
                throw new IllegalStateException("recentTps is not a double[]");
            }
        } catch (Exception | LinkageError e) {
            server = null;
            recentTps = null;
            plugin.getLogger().info("Lag guard: measuring TPS itself (" + e.getClass().getSimpleName() + ").");
            lastSample = System.nanoTime();
            // Every 20 ticks: real TPS = 20 ticks / elapsed seconds, smoothed over ~1, 5 and 15 minutes.
            fallbackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                long now = System.nanoTime();
                double seconds = (now - lastSample) / 1_000_000_000D;
                lastSample = now;
                double tps = seconds <= 0 ? 20 : Math.min(20, 20 / seconds);
                fallback[0] += (tps - fallback[0]) / 60D;
                fallback[1] += (tps - fallback[1]) / 300D;
                fallback[2] += (tps - fallback[2]) / 900D;
            }, 20L, 20L);
        }
    }

    public void shutdown() {
        if (fallbackTask != null) {
            fallbackTask.cancel();
        }
    }

    /** 1, 5 and 15 minute TPS, capped at 20. */
    public double[] tps() {
        double[] values;
        if (recentTps != null) {
            try {
                values = ((double[]) recentTps.get(server)).clone();
            } catch (Exception e) {
                values = fallback.clone();
            }
        } else {
            values = fallback.clone();
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = Math.max(0, Math.min(20, values[i]));
        }
        return values;
    }

    public static String colored(double tps) {
        String color = tps >= 18 ? "&a" : tps >= 15 ? "&e" : "&c";
        return color + String.format(java.util.Locale.US, "%.1f", tps);
    }
}
