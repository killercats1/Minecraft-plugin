package com.killercats.servercore.features;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Locale;

/**
 * Chat format with LuckPerms prefixes/suffixes (read through Vault), e.g. "[Owner] Killercats2154: hi".
 * Steps aside automatically when EssentialsX Chat is installed so the two don't fight over the format.
 */
public final class ChatFormatter implements Listener {

    private final ServerCore plugin;
    private boolean warned;

    public ChatFormatter(ServerCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("chat-format");
        if (c == null || !c.getBoolean("enabled", true)) {
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("EssentialsChat")) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("EssentialsX Chat is installed, so ServerCore's chat-format is not used."
                        + " Remove EssentialsXChat to use ServerCore's chat prefixes.");
            }
            return;
        }
        Player player = event.getPlayer();
        String group = plugin.vault().primaryGroup(player);
        String template = null;
        if (group != null) {
            template = c.getString("group-formats." + group.toLowerCase(Locale.ROOT));
        }
        if (template == null || template.isEmpty()) {
            template = c.getString("format", "{prefix}{displayname}{suffix}&7: &f{message}");
        }
        if (!template.contains("{message}")) {
            template = template + " {message}";
        }

        // Fill in everything except the name and message, then hand those to Bukkit as %1$s / %2$s
        // so other plugins (DiscordSRV, nicknames) still see the real display name and message.
        String filled = plugin.placeholders().apply(player, template)
                .replace("{prefix}", plugin.vault().prefix(player))
                .replace("{suffix}", plugin.vault().suffix(player))
                .replace("{group}", group == null ? "" : Text.capitalize(group))
                .replace("{world}", player.getWorld().getName())
                .replace("{name}", player.getName());
        String format = Text.color(filled.replace("%", "%%"))
                .replace("{displayname}", "%1$s")
                .replace("{message}", ChatColor.RESET + Text.color(c.getString("message-color", "&f")) + "%2$s");
        try {
            event.setFormat(format);
        } catch (Exception e) {
            // A broken format must never stop people from chatting.
            plugin.getLogger().warning("Invalid chat-format: " + e.getMessage());
            return;
        }
        String colorPermission = c.getString("color-permission", "essentials.chat.color");
        if (colorPermission != null && !colorPermission.isEmpty() && player.hasPermission(colorPermission)) {
            event.setMessage(Text.color(event.getMessage()));
        }
    }
}
