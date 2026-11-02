package com.killercats.servercore.combat;

import com.killercats.servercore.ServerCore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat-log protection: hitting or being hit by another player tags both for a few seconds. While
 * tagged, escape commands (/spawn, /home, /tpa, ...) and flying are blocked, and logging out kills
 * the player so their items drop for the attacker.
 */
public final class CombatTag implements Listener {

    private final ServerCore plugin;
    /** Player -> time the tag ends. */
    private final Map<UUID, Long> tagged = new ConcurrentHashMap<UUID, Long>();
    private final Set<UUID> kicked = new HashSet<UUID>();
    private Set<String> blockedCommands = new HashSet<String>();
    private BukkitTask ticker;

    public CombatTag(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        Set<String> commands = new HashSet<String>();
        for (String command : config().getStringList("blocked-commands")) {
            commands.add(command.toLowerCase(Locale.ROOT));
        }
        blockedCommands = commands;
        if (ticker == null) {
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
        }
        tagged.clear();
    }

    private ConfigurationSection config() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("combat-tag");
        return section != null ? section : plugin.getConfig().createSection("combat-tag");
    }

    private boolean enabled() {
        return config().getBoolean("enabled", true);
    }

    public boolean isTagged(Player player) {
        Long until = tagged.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    /** Seconds left on the tag, 0 if not tagged (scoreboard placeholder {combat}). */
    public int secondsLeft(Player player) {
        Long until = tagged.get(player.getUniqueId());
        if (until == null) {
            return 0;
        }
        return (int) Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = tagged.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (entry.getValue() <= now) {
                it.remove();
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    plugin.messages().send(player, "combat.untagged");
                }
            }
        }
    }

    private void tag(Player player, Player opponent) {
        if (player.hasPermission("servercore.combattag.bypass") || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        boolean wasTagged = isTagged(player);
        tagged.put(player.getUniqueId(), System.currentTimeMillis() + Math.max(1, config().getInt("seconds", 15)) * 1000L);
        if (!wasTagged) {
            plugin.messages().send(player, "combat.tagged", "player", opponent.getName(), "seconds", config().getInt("seconds", 15));
        }
        if (config().getBoolean("disable-flight", true) && player.isFlying()) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!enabled() || !(event.getEntity() instanceof Player)) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Entity) {
            damager = (Entity) ((Projectile) damager).getShooter();
        }
        if (!(damager instanceof Player) || damager.equals(event.getEntity())) {
            return;
        }
        Player victim = (Player) event.getEntity();
        Player attacker = (Player) damager;
        tag(victim, attacker);
        tag(attacker, victim);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isTagged(player)) {
            return;
        }
        String raw = event.getMessage();
        int space = raw.indexOf(' ');
        String label = (space < 0 ? raw.substring(1) : raw.substring(1, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (blockedCommands.contains(label) || blockedCommands.contains("*")) {
            event.setCancelled(true);
            plugin.messages().send(player, "combat.command-blocked", "seconds", secondsLeft(player));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFly(PlayerToggleFlightEvent event) {
        if (event.isFlying() && config().getBoolean("disable-flight", true) && isTagged(event.getPlayer())) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "combat.no-flying");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        tagged.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        // Staff kicks, bans and restarts aren't combat logging.
        kicked.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean wasKicked = kicked.remove(player.getUniqueId());
        boolean wasTagged = isTagged(player);
        tagged.remove(player.getUniqueId());
        if (!wasTagged || wasKicked || !enabled() || !plugin.isEnabled() || !config().getBoolean("kill-on-logout", true)) {
            return;
        }
        player.setHealth(0.0);
        String message = plugin.messages().prefix() + plugin.messages().get("combat.logged-out", "player", player.getName());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player)) {
                online.sendMessage(message);
            }
        }
        plugin.getLogger().info(player.getName() + " logged out during combat and was killed.");
    }
}
