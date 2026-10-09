package com.killercats.servercore.features;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Private staff channel: /sc <message>, or /sc to toggle all chat into the staff channel. */
public final class StaffChat extends BaseCommand implements Listener {

    private final Set<UUID> toggled = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    public StaffChat(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.staffchat");
        if (args.length == 0) {
            Player player = requirePlayer(sender);
            if (!toggled.remove(player.getUniqueId())) {
                toggled.add(player.getUniqueId());
                msg(player, "staffchat.toggle-on");
            } else {
                msg(player, "staffchat.toggle-off");
            }
            return;
        }
        send(sender.getName(), Text.join(args, 0));
    }

    public void send(String from, String message) {
        String formatted = Text.color(Text.replace(plugin.getConfig().getString("staffchat.format", "&8[&cStaff&8] &e{player}&8: &f{message}"),
                "player", from)).replace("{message}", message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("servercore.staffchat")) {
                online.sendMessage(formatted);
            }
        }
        Bukkit.getConsoleSender().sendMessage(formatted);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (toggled.contains(player.getUniqueId()) && player.hasPermission("servercore.staffchat")) {
            event.setCancelled(true);
            send(player.getName(), event.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        toggled.remove(event.getPlayer().getUniqueId());
    }
}
