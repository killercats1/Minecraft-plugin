package com.killercats.servercore.command;

import com.killercats.servercore.ServerCore;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the bundled LuckPerms rank setup (luckperms/setup-commands.txt) one command at a time from the
 * console. Some hosting panels join pasted lines into a single command, so pasting the file doesn't work.
 */
final class RankSetup {

    /** Ticks between commands, plus a longer pause after creating a group/track so it exists before it is edited. */
    private static final long STEP_TICKS = 2L;
    private static final long CREATE_PAUSE_TICKS = 20L;

    private final ServerCore plugin;
    private boolean running;

    RankSetup(ServerCore plugin) {
        this.plugin = plugin;
    }

    void run(CommandSender sender, String ownerName) {
        Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) {
            throw new CommandFail("setupranks.no-luckperms");
        }
        if (running) {
            throw new CommandFail("setupranks.already-running");
        }
        List<String> commands = load();
        if (ownerName != null) {
            commands.add("lp user " + ownerName + " parent set owner");
        }
        running = true;
        long seconds = (commands.size() * STEP_TICKS + 12 * CREATE_PAUSE_TICKS) / 20 + 1;
        plugin.messages().send(sender, "setupranks.started", "count", commands.size(), "seconds", seconds);
        long delay = 1L;
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            boolean last = i == commands.size() - 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (last) {
                    running = false;
                    plugin.messages().send(sender, ownerName == null ? "setupranks.done" : "setupranks.done-owner", "player", ownerName);
                }
            }, delay);
            delay += command.startsWith("lp create") ? CREATE_PAUSE_TICKS : STEP_TICKS;
        }
    }

    private List<String> load() {
        List<String> commands = new ArrayList<String>();
        InputStream in = plugin.getResource("setup-commands.txt");
        if (in == null) {
            throw new CommandFail("general.error");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    commands.add(line.startsWith("/") ? line.substring(1) : line);
                }
            }
        } catch (IOException e) {
            throw new CommandFail("general.error");
        }
        return commands;
    }
}
