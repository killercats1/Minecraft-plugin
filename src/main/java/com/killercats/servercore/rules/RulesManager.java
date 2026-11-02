package com.killercats.servercore.rules;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.CommandFail;
import com.killercats.servercore.util.Text;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows the server rules to new players (and to everyone again when rules.version goes up) and keeps
 * them in place until they click "I accept" or type /rules accept.
 */
public final class RulesManager implements Listener {

    private final ServerCore plugin;
    private final Set<UUID> pending = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    private BukkitTask reminder;

    public RulesManager(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (reminder != null) {
            reminder.cancel();
            reminder = null;
        }
        long ticks = Math.max(5, config().getLong("remind-seconds", 15)) * 20L;
        reminder = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : pending) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    sendAcceptButton(player);
                }
            }
        }, ticks, ticks);
        if (!requireAccept()) {
            pending.clear();
        }
    }

    public void shutdown() {
        if (reminder != null) {
            reminder.cancel();
        }
    }

    private ConfigurationSection config() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("rules");
        return section != null ? section : plugin.getConfig().createSection("rules");
    }

    private boolean requireAccept() {
        return config().getBoolean("require-accept", true);
    }

    private int version() {
        return config().getInt("version", 1);
    }

    public boolean isPending(Player player) {
        return pending.contains(player.getUniqueId());
    }

    public void show(CommandSender sender) {
        List<String> lines = config().getStringList("lines");
        plugin.messages().sendRaw(sender, "rules.header");
        int number = 1;
        for (String line : lines) {
            sender.sendMessage(Text.color(plugin.messages().get("rules.line", "number", number++, "rule", line)));
        }
    }

    private void sendAcceptButton(Player player) {
        TextComponent button = new TextComponent(TextComponent.fromLegacyText(plugin.messages().get("rules.accept-button")));
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rules accept"));
        button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[]{new TextComponent(plugin.messages().get("rules.accept-hover"))}));
        player.spigot().sendMessage(button);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!requireAccept() || player.hasPermission("servercore.rules.exempt")) {
            return;
        }
        UUID uuid = player.getUniqueId();
        int version = version();
        plugin.database().query("SELECT version FROM sc_rules WHERE uuid = ?", rs -> rs.next() ? rs.getInt(1) : 0, uuid.toString())
                .whenComplete((accepted, error) -> plugin.sync(() -> {
                    if (error != null || !player.isOnline() || (accepted != null && accepted >= version)) {
                        return;
                    }
                    pending.add(uuid);
                    // A short delay so login plugin prompts and the MOTD don't bury the rules.
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && isPending(player)) {
                            show(player);
                            sendAcceptButton(player);
                        }
                    }, 40L);
                }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    public void accept(Player player) {
        if (!pending.remove(player.getUniqueId())) {
            throw new CommandFail("rules.already-accepted");
        }
        plugin.database().update(plugin.database().upsert("sc_rules", "uuid", "version", "accepted_at"),
                player.getUniqueId().toString(), version(), System.currentTimeMillis());
        plugin.messages().send(player, "rules.accepted");
    }

    // ------------------------------------------------------------------ keep pending players in place

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isPending(event.getPlayer())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && (from.getBlockX() != to.getBlockX() || from.getBlockZ() != to.getBlockZ() || to.getY() > from.getY() + 0.1)) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (isPending(event.getPlayer())) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "rules.accept-first");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isPending(event.getPlayer())) {
            return;
        }
        String raw = event.getMessage();
        int space = raw.indexOf(' ');
        String label = (space < 0 ? raw.substring(1) : raw.substring(1, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        Set<String> allowed = new HashSet<String>();
        allowed.add("rules");
        for (String command : config().getStringList("allowed-commands")) {
            allowed.add(command.toLowerCase(Locale.ROOT));
        }
        if (!allowed.contains(label)) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "rules.accept-first");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isPending(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isPending(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        if (isPending(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && isPending((Player) event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) event).getDamager() instanceof Player
                && isPending((Player) ((EntityDamageByEntityEvent) event).getDamager())) {
            event.setCancelled(true);
        }
    }

    /** /rules [accept] */
    public static final class RulesCommand extends BaseCommand {

        public RulesCommand(ServerCore plugin) {
            super(plugin);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            RulesManager rules = plugin.rules();
            if (args.length > 0 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("agree"))) {
                rules.accept(requirePlayer(sender));
                return;
            }
            rules.show(sender);
            if (sender instanceof Player && rules.isPending((Player) sender)) {
                rules.sendAcceptButton((Player) sender);
            }
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 && sender instanceof Player && plugin.rules().isPending((Player) sender) ? list("accept") : list();
        }
    }
}
