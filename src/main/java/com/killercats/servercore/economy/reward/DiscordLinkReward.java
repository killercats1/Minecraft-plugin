package com.killercats.servercore.economy.reward;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Sounds;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * One-time reward for linking a Discord account through DiscordSRV. DiscordSRV runs
 * "servercore linkreward %minecraftuuid% %discordid%" as console when an account is linked; the
 * reward is recorded per Minecraft account and per Discord account, so unlinking and relinking
 * (or linking another Discord account) can't farm it.
 */
public final class DiscordLinkReward {

    private final ServerCore plugin;

    public DiscordLinkReward(ServerCore plugin) {
        this.plugin = plugin;
    }

    public void claim(CommandSender sender, String uuidText, String discordId) {
        if (!plugin.getConfig().getBoolean("discord-link-reward.enabled", true)) {
            plugin.messages().send(sender, "linkreward.disabled");
            return;
        }
        if (!plugin.economyReady()) {
            plugin.messages().send(sender, "general.economy-unavailable");
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            plugin.messages().send(sender, "linkreward.bad-uuid", "input", uuidText);
            return;
        }
        if (!discordId.matches("\\d{5,25}")) {
            plugin.messages().send(sender, "linkreward.bad-discord-id", "input", discordId);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
        String name = target.getName() == null ? uuidText : target.getName();
        plugin.database().query("SELECT uuid FROM sc_link_rewards WHERE uuid = ? OR discord_id = ?", rs -> rs.next(), uuid.toString(), discordId)
                .thenCompose(claimed -> {
                    if (claimed) {
                        return java.util.concurrent.CompletableFuture.completedFuture(false);
                    }
                    // The unique keys make a second claim fail even if two links race each other.
                    return plugin.database().update("INSERT INTO sc_link_rewards (uuid, discord_id, name, rewarded_at) VALUES (?, ?, ?, ?)",
                            uuid.toString(), discordId, name, System.currentTimeMillis()).thenApply(rows -> rows == 1);
                })
                .whenComplete((granted, error) -> plugin.sync(() -> {
                    if (error != null) {
                        plugin.messages().send(sender, "linkreward.already-claimed", "player", name);
                        return;
                    }
                    if (!granted) {
                        plugin.messages().send(sender, "linkreward.already-claimed", "player", name);
                        Player online = Bukkit.getPlayer(uuid);
                        if (online != null) {
                            plugin.messages().send(online, "linkreward.already-claimed-player");
                        }
                        return;
                    }
                    give(sender, target, uuid, name, discordId);
                }));
    }

    private void give(CommandSender sender, OfflinePlayer target, UUID uuid, String name, String discordId) {
        double money = Money.round(plugin.getConfig().getDouble("discord-link-reward.money", 100));
        if (money > 0) {
            if (plugin.economy().deposit(target, money)) {
                plugin.transactions().log(TransactionType.DISCORD_LINK, uuid, null, money, "Linked Discord " + discordId);
            } else {
                plugin.getLogger().warning("Could not pay the Discord link reward to " + name);
            }
        }
        for (String command : plugin.getConfig().getStringList("discord-link-reward.commands")) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            String line = Text.replace(command.trim(), "player", name, "uuid", uuid, "discordid", discordId);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
        plugin.messages().send(sender, "linkreward.granted", "player", name, "amount", Money.format(money));
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            plugin.messages().send(online, "linkreward.received", "amount", Money.format(money));
            Sounds.play(online, plugin.getConfig().getString("sounds.success", "LEVEL_UP"));
        }
        if (plugin.getConfig().getBoolean("discord-link-reward.broadcast", true)) {
            String message = plugin.messages().prefix() + plugin.messages().get("linkreward.broadcast", "player", name, "amount", Money.format(money));
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(message);
            }
        }
    }
}
