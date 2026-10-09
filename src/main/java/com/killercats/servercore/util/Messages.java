package com.killercats.servercore.util;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads messages.yml. Missing keys are filled in from the copy bundled in the jar so upgrading
 * the plugin never leaves blank messages.
 */
public final class Messages {

    private final JavaPlugin plugin;
    private YamlConfiguration config;
    private String prefix;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        InputStream defaults = plugin.getResource("messages.yml");
        if (defaults != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        prefix = Text.color(config.getString("prefix", "&8[&6Server&8] &7"));
    }

    public String prefix() {
        return prefix;
    }

    /** Returns the formatted message without prefix. Lists are joined with new lines. */
    public String get(String key, Object... placeholders) {
        String raw;
        if (config.isList(key)) {
            List<String> lines = config.getStringList(key);
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            raw = sb.toString();
        } else {
            raw = config.getString(key);
        }
        if (raw == null) {
            return "Missing message: " + key;
        }
        return Text.color(Text.replace(raw, placeholders));
    }

    public List<String> getList(String key, Object... placeholders) {
        List<String> lines = config.getStringList(key);
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, Text.color(Text.replace(lines.get(i), placeholders)));
        }
        return lines;
    }

    /** Sends a message with the prefix. Empty messages are skipped so admins can disable them. */
    public void send(CommandSender sender, String key, Object... placeholders) {
        String message = get(key, placeholders);
        if (message.isEmpty()) {
            return;
        }
        for (String line : message.split("\n")) {
            sender.sendMessage(prefix + line);
        }
    }

    /** Sends a message without the prefix (headers, lists, multi-line blocks). */
    public void sendRaw(CommandSender sender, String key, Object... placeholders) {
        String message = get(key, placeholders);
        if (message.isEmpty()) {
            return;
        }
        for (String line : message.split("\n")) {
            sender.sendMessage(line);
        }
    }
}
