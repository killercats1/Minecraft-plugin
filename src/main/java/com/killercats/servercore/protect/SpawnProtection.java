package com.killercats.servercore.protect;

import com.killercats.servercore.ServerCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the spawn area safe without WorldGuard: no building, breaking, explosions, fire, PvP or
 * damage inside a radius around the world spawn (or a centre set with /servercore setspawnprotection).
 * Players with servercore.spawnprotection.bypass (builders, admins) can still build.
 */
public final class SpawnProtection implements Listener {

    private final ServerCore plugin;
    private final Map<UUID, Long> lastWarning = new HashMap<UUID, Long>();

    public SpawnProtection(ServerCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection config() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("spawn-protection");
        return section != null ? section : plugin.getConfig().createSection("spawn-protection");
    }

    private Location centre() {
        ConfigurationSection c = config();
        String worldName = c.getString("world", "");
        World world = worldName == null || worldName.isEmpty() ? Bukkit.getWorlds().get(0) : Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        if (c.isConfigurationSection("center")) {
            return new Location(world, c.getDouble("center.x"), 0, c.getDouble("center.z"));
        }
        return world.getSpawnLocation();
    }

    /** Whether a location is inside the protected area. */
    public boolean isProtected(Location location) {
        ConfigurationSection c = config();
        if (location == null || !c.getBoolean("enabled", true)) {
            return false;
        }
        Location centre = centre();
        if (centre == null || !centre.getWorld().equals(location.getWorld())) {
            return false;
        }
        int radius = c.getInt("radius", 32);
        return Math.abs(location.getBlockX() - centre.getBlockX()) <= radius && Math.abs(location.getBlockZ() - centre.getBlockZ()) <= radius;
    }

    private boolean blocked(Player player, Location location) {
        return isProtected(location) && !player.hasPermission("servercore.spawnprotection.bypass");
    }

    private void warn(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last == null || now - last > 3000) {
            lastWarning.put(player.getUniqueId(), now);
            plugin.messages().send(player, "spawnprotection.denied");
        }
    }

    /** Saves the given location as the centre (used by /servercore setspawnprotection). */
    public void setCentre(Location location) {
        plugin.getConfig().set("spawn-protection.world", location.getWorld().getName());
        plugin.getConfig().set("spawn-protection.center.x", location.getBlockX());
        plugin.getConfig().set("spawn-protection.center.z", location.getBlockZ());
        plugin.saveConfig();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            warn(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            warn(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (blocked(event.getPlayer(), event.getBlockClicked().getRelative(event.getBlockFace()).getLocation())) {
            event.setCancelled(true);
            warn(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (blocked(event.getPlayer(), event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
            warn(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !blocked(event.getPlayer(), block.getLocation())) {
            return;
        }
        // Trampling farmland, lighting fires and using flint/fire charges are building; doors and buttons are fine.
        if (event.getAction() == Action.PHYSICAL && block.getType() == Material.SOIL) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getItem() != null) {
            Material item = event.getItem().getType();
            if (item == Material.FLINT_AND_STEEL || item == Material.FIREBALL || item == Material.MONSTER_EGG
                    || item == Material.ARMOR_STAND || item == Material.ITEM_FRAME || item == Material.PAINTING) {
                event.setCancelled(true);
                warn(event.getPlayer());
            }
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && config().getBoolean("block-containers", false)) {
            switch (block.getType()) {
                case CHEST:
                case TRAPPED_CHEST:
                case FURNACE:
                case BURNING_FURNACE:
                case DISPENSER:
                case DROPPER:
                case HOPPER:
                case BREWING_STAND:
                    event.setCancelled(true);
                    warn(event.getPlayer());
                    break;
                default:
                    break;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        // Item frames: rotating/taking items.
        if (event.getRightClicked() instanceof org.bukkit.entity.ItemFrame && blocked(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (!isProtected(event.getEntity().getLocation())) {
            return;
        }
        Entity remover = event.getRemover();
        if (remover instanceof Player && ((Player) remover).hasPermission("servercore.spawnprotection.bypass")) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player) {
            return; // handled in onDamage
        }
        // Protect item frames, armor stands and animals at spawn from players.
        Entity damager = event.getDamager();
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Entity) {
            damager = (Entity) ((Projectile) damager).getShooter();
        }
        if (damager instanceof Player && config().getBoolean("protect-animals", true)
                && !(event.getEntity() instanceof org.bukkit.entity.Monster)
                && blocked((Player) damager, event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player) || !isProtected(event.getEntity().getLocation())) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent) {
            Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
            if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Entity) {
                damager = (Entity) ((Projectile) damager).getShooter();
            }
            if (damager instanceof Player) {
                if (config().getBoolean("block-pvp", true)) {
                    event.setCancelled(true);
                }
                return;
            }
        }
        if (config().getBoolean("block-all-damage", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            if (isProtected(it.next().getLocation())) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!isProtected(event.getBlock().getLocation())) {
            return;
        }
        if (event.getPlayer() != null && event.getPlayer().hasPermission("servercore.spawnprotection.bypass")) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getNewState().getType() == Material.FIRE && isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        // Lava/water poured just outside must not flow into spawn.
        if (isProtected(event.getToBlock().getLocation()) && !isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (isProtected(block.getRelative(event.getDirection()).getLocation()) && !isProtected(event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEndermanGrief(EntityChangeBlockEvent event) {
        org.bukkit.entity.EntityType type = event.getEntityType();
        if ((type == org.bukkit.entity.EntityType.ENDERMAN || type == org.bukkit.entity.EntityType.WITHER
                || type == org.bukkit.entity.EntityType.SILVERFISH) && isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }
}
