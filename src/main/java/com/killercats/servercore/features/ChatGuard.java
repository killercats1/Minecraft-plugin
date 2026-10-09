package com.killercats.servercore.features;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat protection for a public server: message cooldown, repeated-message blocking, advertising
 * (IP addresses and domains) blocking with staff alerts, a caps limiter and a word filter.
 * Also checks private-message commands for advertising and filtered words.
 */
public final class ChatGuard implements Listener {

    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}\\s*[.,]\\s*\\d{1,3}\\s*[.,]\\s*\\d{1,3}\\s*[.,]\\s*\\d{1,3}\\b");
    /** "name.tld", "name(dot)tld", "name dot tld" for common TLDs. */
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9-]{2,}(\\.|\\s*\\(dot\\)\\s*|\\s*\\[dot\\]\\s*|\\sdot\\s)"
            + "(com|net|org|gg|io|me|host|xyz|tk|ml|ga|cf|gq|us|co|uk|de|fun|club|online|site|pw|cc|ws|eu|nl|fr|ru|tv|live|world|pro|ly|link|store|top)\\b");
    /** "name . com" with spaces, only for endings that don't appear in normal sentences. */
    private static final Pattern SPACED_DOMAIN = Pattern.compile("[a-z0-9-]{2,}\\s*\\.\\s*(com|net|org|gg|host|xyz)\\b");

    private final ServerCore plugin;
    private final Map<UUID, Long> lastTime = new ConcurrentHashMap<UUID, Long>();
    private final Map<UUID, String> lastMessage = new ConcurrentHashMap<UUID, String>();
    private volatile List<Pattern> blockedWords = new ArrayList<Pattern>();
    private volatile Set<String> checkedCommands = new HashSet<String>();

    public ChatGuard(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        List<Pattern> words = new ArrayList<Pattern>();
        for (String word : plugin.getConfig().getStringList("chat-guard.blocked-words")) {
            if (word != null && !word.trim().isEmpty()) {
                words.add(Pattern.compile("(?i)(?<![a-z0-9])" + Pattern.quote(word.trim()) + "(?![a-z0-9])"));
            }
        }
        blockedWords = words;
        Set<String> commands = new HashSet<String>();
        for (String command : plugin.getConfig().getStringList("chat-guard.check-commands")) {
            commands.add(command.toLowerCase(Locale.ROOT));
        }
        checkedCommands = commands;
    }

    private ConfigurationSection config() {
        return plugin.getConfig().getConfigurationSection("chat-guard");
    }

    private boolean active(Player player) {
        ConfigurationSection c = config();
        return c != null && c.getBoolean("enabled", true) && !player.hasPermission("servercore.chatguard.bypass");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!active(player)) {
            return;
        }
        ConfigurationSection c = config();
        String message = event.getMessage();
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();

        Long last = lastTime.get(uuid);
        long cooldown = c.getLong("cooldown-millis", 1500);
        if (last != null && now - last < cooldown) {
            event.setCancelled(true);
            plugin.messages().send(player, "chatguard.slow-down");
            return;
        }
        String normalized = Text.strip(message).trim().toLowerCase(Locale.ROOT);
        String previous = lastMessage.get(uuid);
        if (c.getBoolean("block-repeats", true) && previous != null && previous.equals(normalized) && last != null
                && now - last < c.getLong("repeat-window-seconds", 30) * 1000L) {
            event.setCancelled(true);
            plugin.messages().send(player, "chatguard.repeat");
            return;
        }
        if (c.getBoolean("block-advertising", true) && isAdvertising(message)) {
            event.setCancelled(true);
            plugin.messages().send(player, "chatguard.advertising");
            alertStaff(player, message);
            return;
        }
        lastTime.put(uuid, now);
        lastMessage.put(uuid, normalized);
        event.setMessage(filterWords(fixCaps(message)));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!active(player)) {
            return;
        }
        String raw = event.getMessage();
        int space = raw.indexOf(' ');
        if (space < 0) {
            return;
        }
        String label = raw.substring(1, space).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (!checkedCommands.contains(label)) {
            return;
        }
        String args = raw.substring(space + 1);
        if (config().getBoolean("block-advertising", true) && isAdvertising(args)) {
            event.setCancelled(true);
            plugin.messages().send(player, "chatguard.advertising");
            alertStaff(player, raw);
            return;
        }
        String filtered = filterWords(args);
        if (!filtered.equals(args)) {
            event.setMessage(raw.substring(0, space + 1) + filtered);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastTime.remove(event.getPlayer().getUniqueId());
        lastMessage.remove(event.getPlayer().getUniqueId());
    }

    boolean isAdvertising(String message) {
        String text = Text.strip(message).toLowerCase(Locale.ROOT);
        for (String allowed : config().getStringList("allowed-links")) {
            if (allowed != null && !allowed.isEmpty()) {
                text = text.replace(allowed.toLowerCase(Locale.ROOT), " ");
            }
        }
        return IP.matcher(text).find() || DOMAIN.matcher(text).find() || SPACED_DOMAIN.matcher(text).find();
    }

    private String fixCaps(String message) {
        int limit = config().getInt("caps-limit-percent", 60);
        if (limit <= 0 || limit >= 100) {
            return message;
        }
        int letters = 0;
        int upper = 0;
        for (char ch : message.toCharArray()) {
            if (Character.isLetter(ch)) {
                letters++;
                if (Character.isUpperCase(ch)) {
                    upper++;
                }
            }
        }
        if (letters < config().getInt("caps-min-length", 6) || upper * 100 <= letters * limit) {
            return message;
        }
        return message.toLowerCase(Locale.ROOT);
    }

    private String filterWords(String message) {
        String out = message;
        for (Pattern pattern : blockedWords) {
            Matcher matcher = pattern.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                StringBuilder stars = new StringBuilder();
                for (int i = 0; i < matcher.group().length(); i++) {
                    stars.append('*');
                }
                matcher.appendReplacement(sb, stars.toString());
            }
            matcher.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    private void alertStaff(Player player, String message) {
        String alert = plugin.messages().prefix() + plugin.messages().get("chatguard.advertising-alert", "player", player.getName())
                + Text.strip(message);
        plugin.sync(() -> {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("servercore.chatguard.notify")) {
                    staff.sendMessage(alert);
                }
            }
            Bukkit.getConsoleSender().sendMessage(alert);
        });
    }
}
