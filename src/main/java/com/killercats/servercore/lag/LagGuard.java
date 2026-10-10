package com.killercats.servercore.lag;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.economy.notes.BanknoteManager;
import com.killercats.servercore.util.Text;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the server smooth on a small host with browser (Eaglercraft) players:
 * - clears old dropped items on a timer (with warnings), never valuable/named/enchanted items or death drops;
 * - caps breeding/spawner/egg mobs per chunk (spawns are blocked, existing mobs are never killed);
 * - emergency mode when TPS stays low: early item clear, natural spawns paused, staff get the laggiest chunks.
 */
public final class LagGuard implements Listener {

    /** One entry of the "busiest chunks" report. */
    public static final class ChunkReport {
        public final String world;
        public final int x;
        public final int z;
        public final int entities;
        public final String top;

        ChunkReport(String world, int x, int z, int entities, String top) {
            this.world = world;
            this.x = x;
            this.z = z;
            this.entities = entities;
            this.top = top;
        }
    }

    private static final class DeathMark {
        final UUID world;
        final double x;
        final double y;
        final double z;
        final long time;

        DeathMark(Location location) {
            this.world = location.getWorld().getUID();
            this.x = location.getX();
            this.y = location.getY();
            this.z = location.getZ();
            this.time = System.currentTimeMillis();
        }
    }

    private final ServerCore plugin;
    private final TpsMeter meter;
    private final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
    private final List<DeathMark> deaths = new ArrayList<DeathMark>();
    private final Map<UUID, Long> notified = new HashMap<UUID, Long>();
    private final Set<Integer> warned = new HashSet<Integer>();

    // settings
    private boolean sweepEnabled;
    private long sweepIntervalMillis;
    private int minAgeTicks;
    private final Set<Integer> warnSeconds = new HashSet<Integer>();
    private boolean sweepBroadcast;
    private boolean skipNamed;
    private boolean skipEnchanted;
    private final Set<Material> keepMaterials = EnumSet.noneOf(Material.class);
    private long deathProtectionMillis;
    private double deathRadiusSquared;
    private final Set<String> excludedWorlds = new HashSet<String>();
    private boolean capsEnabled;
    private final Set<CreatureSpawnEvent.SpawnReason> capReasons = EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
    private int perTypePerChunk;
    private final Map<EntityType, Integer> overrides = new EnumMap<EntityType, Integer>(EntityType.class);
    private int animalsPerChunk;
    private int notifyRadius;
    private boolean emergencyEnabled;
    private double enterBelow;
    private long sustainMillis;
    private double exitAbove;
    private boolean earlySweep;
    private boolean blockNaturalSpawns;
    private int alertTopChunks;
    private long alertCooldownMillis;

    // state
    private long nextSweep;
    private long lastSweepTime;
    private int lastSweepRemoved;
    private long spawnsBlocked;
    private boolean emergency;
    private long lowSince;
    private long highSince;
    private long lastAlert;

    public LagGuard(ServerCore plugin) {
        this.plugin = plugin;
        this.meter = new TpsMeter(plugin);
        reload();
    }

    public TpsMeter meter() {
        return meter;
    }

    public void reload() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("lag-guard");
        if (c == null || !c.getBoolean("enabled", true)) {
            sweepEnabled = false;
            capsEnabled = false;
            emergencyEnabled = false;
            emergency = false;
            return;
        }
        excludedWorlds.clear();
        for (String world : c.getStringList("excluded-worlds")) {
            excludedWorlds.add(world.toLowerCase(Locale.ROOT));
        }

        sweepEnabled = c.getBoolean("item-sweep.enabled", true);
        sweepIntervalMillis = Math.max(1, c.getLong("item-sweep.interval-minutes", 10)) * 60_000L;
        minAgeTicks = Math.max(0, c.getInt("item-sweep.min-age-seconds", 180)) * 20;
        warnSeconds.clear();
        warnSeconds.addAll(c.getIntegerList("item-sweep.warn-seconds"));
        sweepBroadcast = c.getBoolean("item-sweep.broadcast", true);
        skipNamed = c.getBoolean("item-sweep.skip-named-or-lored", true);
        skipEnchanted = c.getBoolean("item-sweep.skip-enchanted", true);
        keepMaterials.clear();
        for (String name : c.getStringList("item-sweep.keep-materials")) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                plugin.getLogger().warning("lag-guard.item-sweep.keep-materials: unknown material '" + name + "'");
            } else {
                keepMaterials.add(material);
            }
        }
        deathProtectionMillis = Math.max(0, c.getLong("item-sweep.death-protection-minutes", 5)) * 60_000L;
        double radius = Math.max(0, c.getDouble("item-sweep.death-protection-radius", 10));
        deathRadiusSquared = radius * radius;
        nextSweep = System.currentTimeMillis() + sweepIntervalMillis;
        warned.clear();

        capsEnabled = c.getBoolean("spawn-caps.enabled", true);
        capReasons.clear();
        for (String name : c.getStringList("spawn-caps.reasons")) {
            try {
                capReasons.add(CreatureSpawnEvent.SpawnReason.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("lag-guard.spawn-caps.reasons: unknown spawn reason '" + name + "'");
            }
        }
        perTypePerChunk = Math.max(1, c.getInt("spawn-caps.per-type-per-chunk", 24));
        overrides.clear();
        ConfigurationSection over = c.getConfigurationSection("spawn-caps.overrides");
        if (over != null) {
            for (String key : over.getKeys(false)) {
                try {
                    overrides.put(EntityType.valueOf(key.toUpperCase(Locale.ROOT)), Math.max(1, over.getInt(key)));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("lag-guard.spawn-caps.overrides: unknown entity type '" + key + "'");
                }
            }
        }
        animalsPerChunk = Math.max(1, c.getInt("spawn-caps.animals-per-chunk", 48));
        notifyRadius = Math.max(0, c.getInt("spawn-caps.notify-radius", 8));

        emergencyEnabled = c.getBoolean("emergency.enabled", true);
        enterBelow = c.getDouble("emergency.enter-below", 15.0);
        sustainMillis = Math.max(5, c.getLong("emergency.sustain-seconds", 60)) * 1000L;
        exitAbove = c.getDouble("emergency.exit-above", 18.0);
        earlySweep = c.getBoolean("emergency.early-sweep", true);
        blockNaturalSpawns = c.getBoolean("emergency.block-natural-spawns", true);
        alertTopChunks = Math.max(1, c.getInt("emergency.alert-top-chunks", 5));
        alertCooldownMillis = Math.max(1, c.getLong("emergency.alert-cooldown-minutes", 5)) * 60_000L;
        if (!emergencyEnabled) {
            emergency = false;
        }

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickSweep, 20L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickEmergency, 100L, 100L));
    }

    public void shutdown() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        meter.shutdown();
    }

    // ------------------------------------------------------------------ item sweep

    private void tickSweep() {
        if (!sweepEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        int secondsLeft = (int) ((nextSweep - now + 999) / 1000);
        if (secondsLeft <= 0) {
            sweep(true);
            nextSweep = now + sweepIntervalMillis;
            warned.clear();
            return;
        }
        if (warnSeconds.contains(secondsLeft) && warned.add(secondsLeft) && sweepBroadcast && !Bukkit.getOnlinePlayers().isEmpty()) {
            broadcast("lagguard.sweep-warning", "seconds", secondsLeft);
        }
    }

    /** Schedules a sweep in the given number of seconds (with the normal warnings). */
    public void sweepIn(int seconds) {
        nextSweep = System.currentTimeMillis() + seconds * 1000L;
        warned.clear();
        if (sweepBroadcast) {
            broadcast("lagguard.sweep-warning", "seconds", seconds);
            warned.add(seconds);
        }
    }

    /** Removes old ground items. Returns how many were removed. */
    public int sweep(boolean announce) {
        pruneDeaths();
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            if (excludedWorlds.contains(world.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (!item.isValid() || keep(item)) {
                    continue;
                }
                item.remove();
                removed++;
            }
        }
        lastSweepTime = System.currentTimeMillis();
        lastSweepRemoved = removed;
        if (removed > 0) {
            plugin.getLogger().info("Lag guard: removed " + removed + " ground items.");
            if (announce && sweepBroadcast) {
                broadcast("lagguard.swept", "count", removed);
            }
        }
        return removed;
    }

    private boolean keep(Item item) {
        if (item.getTicksLived() < minAgeTicks || item.isInsideVehicle() || item.isCustomNameVisible()) {
            return true;
        }
        // 32767 is vanilla's "can never be picked up" marker used by display/showcase items.
        if (item.getPickupDelay() >= 32767) {
            return true;
        }
        ItemStack stack = item.getItemStack();
        if (stack == null || keepMaterials.contains(stack.getType())) {
            return true;
        }
        if (BanknoteManager.readId(stack) != null) {
            return true;
        }
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (skipNamed && (meta.hasDisplayName() || meta.hasLore())) {
                return true;
            }
            if (skipEnchanted && (meta.hasEnchants() || (meta instanceof EnchantmentStorageMeta
                    && ((EnchantmentStorageMeta) meta).hasStoredEnchants()))) {
                return true;
            }
        }
        return nearRecentDeath(item.getLocation());
    }

    private void pruneDeaths() {
        long cutoff = System.currentTimeMillis() - deathProtectionMillis;
        Iterator<DeathMark> iterator = deaths.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().time < cutoff) {
                iterator.remove();
            }
        }
    }

    private boolean nearRecentDeath(Location location) {
        if (deathProtectionMillis <= 0 || deaths.isEmpty()) {
            return false;
        }
        UUID world = location.getWorld().getUID();
        for (DeathMark mark : deaths) {
            double dx = mark.x - location.getX();
            double dy = mark.y - location.getY();
            double dz = mark.z - location.getZ();
            if (mark.world.equals(world) && dx * dx + dy * dy + dz * dz <= deathRadiusSquared) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (deathProtectionMillis > 0) {
            pruneDeaths();
            deaths.add(new DeathMark(event.getEntity().getLocation()));
        }
    }

    // ------------------------------------------------------------------ spawn caps

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (emergency && blockNaturalSpawns
                && (reason == CreatureSpawnEvent.SpawnReason.NATURAL || reason == CreatureSpawnEvent.SpawnReason.JOCKEY)) {
            event.setCancelled(true);
            spawnsBlocked++;
            return;
        }
        if (!capsEnabled || !capReasons.contains(reason) || event.getLocation().getWorld() == null
                || excludedWorlds.contains(event.getLocation().getWorld().getName().toLowerCase(Locale.ROOT))) {
            return;
        }
        Entity spawning = event.getEntity();
        EntityType type = spawning.getType();
        boolean animal = spawning instanceof Animals;
        Chunk chunk = event.getLocation().getChunk();
        int sameType = 0;
        int animals = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity.getType() == type) {
                sameType++;
            }
            if (animal && entity instanceof Animals) {
                animals++;
            }
        }
        Integer cap = overrides.get(type);
        boolean blocked = sameType >= (cap == null ? perTypePerChunk : cap) || (animal && animals >= animalsPerChunk);
        if (!blocked) {
            return;
        }
        event.setCancelled(true);
        spawnsBlocked++;
        if (notifyRadius > 0) {
            long now = System.currentTimeMillis();
            double max = notifyRadius * notifyRadius;
            for (Player player : event.getLocation().getWorld().getPlayers()) {
                if (player.getLocation().distanceSquared(event.getLocation()) > max) {
                    continue;
                }
                Long last = notified.get(player.getUniqueId());
                if (last == null || now - last > 30_000L) {
                    notified.put(player.getUniqueId(), now);
                    plugin.messages().send(player, "lagguard.spawn-capped", "type", Text.capitalize(type.name()));
                }
            }
        }
    }

    // ------------------------------------------------------------------ emergency mode

    private void tickEmergency() {
        if (!emergencyEnabled) {
            return;
        }
        double tps = meter.tps()[0];
        long now = System.currentTimeMillis();
        if (!emergency) {
            if (tps < enterBelow) {
                if (lowSince == 0) {
                    lowSince = now;
                } else if (now - lowSince >= sustainMillis) {
                    enterEmergency(tps);
                }
            } else {
                lowSince = 0;
            }
        } else {
            if (tps > exitAbove) {
                if (highSince == 0) {
                    highSince = now;
                } else if (now - highSince >= 60_000L) {
                    emergency = false;
                    highSince = 0;
                    lowSince = 0;
                    notifyStaff("lagguard.emergency-end", "tps", TpsMeter.colored(tps));
                }
            } else {
                highSince = 0;
            }
        }
    }

    private void enterEmergency(double tps) {
        emergency = true;
        lowSince = 0;
        highSince = 0;
        plugin.getLogger().warning("Lag guard: TPS " + String.format(Locale.US, "%.1f", tps) + " - emergency mode on.");
        if (earlySweep && sweepEnabled) {
            sweepIn(10);
        }
        long now = System.currentTimeMillis();
        if (now - lastAlert >= alertCooldownMillis) {
            lastAlert = now;
            notifyStaff("lagguard.emergency-start", "tps", TpsMeter.colored(tps));
            List<ChunkReport> top = busiestChunks(alertTopChunks);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("servercore.lagguard.notify")) {
                    sendChunks(staff, top);
                }
            }
            sendChunks(Bukkit.getConsoleSender(), top);
        }
    }

    public boolean inEmergency() {
        return emergency;
    }

    // ------------------------------------------------------------------ reports

    public List<ChunkReport> busiestChunks(int limit) {
        List<ChunkReport> all = new ArrayList<ChunkReport>();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                Entity[] entities = chunk.getEntities();
                if (entities.length == 0) {
                    continue;
                }
                all.add(new ChunkReport(world.getName(), chunk.getX(), chunk.getZ(), entities.length, null));
            }
        }
        all.sort((a, b) -> Integer.compare(b.entities, a.entities));
        List<ChunkReport> top = new ArrayList<ChunkReport>();
        for (int i = 0; i < Math.min(limit, all.size()); i++) {
            ChunkReport report = all.get(i);
            World world = Bukkit.getWorld(report.world);
            Map<EntityType, Integer> counts = new EnumMap<EntityType, Integer>(EntityType.class);
            if (world != null && world.isChunkLoaded(report.x, report.z)) {
                for (Entity entity : world.getChunkAt(report.x, report.z).getEntities()) {
                    Integer count = counts.get(entity.getType());
                    counts.put(entity.getType(), count == null ? 1 : count + 1);
                }
            }
            List<Map.Entry<EntityType, Integer>> entries = new ArrayList<Map.Entry<EntityType, Integer>>(counts.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder types = new StringBuilder();
            for (int j = 0; j < Math.min(3, entries.size()); j++) {
                if (j > 0) {
                    types.append(", ");
                }
                types.append(entries.get(j).getValue()).append(' ').append(Text.capitalize(entries.get(j).getKey().name()));
            }
            top.add(new ChunkReport(report.world, report.x, report.z, report.entities, types.toString()));
        }
        return top;
    }

    /** Sends the chunk list; for players each line is clickable (teleports with /lagguard tp). */
    public void sendChunks(CommandSender sender, List<ChunkReport> chunks) {
        if (chunks.isEmpty()) {
            plugin.messages().sendRaw(sender, "general.nothing-here");
            return;
        }
        int rank = 1;
        for (ChunkReport report : chunks) {
            String line = plugin.messages().get("lagguard.chunk-line", "rank", rank++, "world", report.world, "x", report.x, "z", report.z,
                    "count", report.entities, "types", report.top);
            if (sender instanceof Player) {
                // 1.8-era chat API only: the server's bundled chat library is older than the one we compile against.
                TextComponent component = new TextComponent(TextComponent.fromLegacyText(line));
                component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/lagguard tp " + report.world + " " + report.x + " " + report.z));
                component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new BaseComponent[]{new TextComponent(plugin.messages().get("lagguard.chunk-hover"))}));
                ((Player) sender).spigot().sendMessage(component);
            } else {
                sender.sendMessage(line);
            }
        }
    }

    public Map<String, int[]> entityCounts() {
        Map<String, int[]> result = new LinkedHashMap<String, int[]>();
        for (World world : Bukkit.getWorlds()) {
            int[] counts = new int[5];
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item) {
                    counts[0]++;
                } else if (entity instanceof Monster) {
                    counts[1]++;
                } else if (entity instanceof Animals) {
                    counts[2]++;
                } else {
                    counts[3]++;
                }
            }
            counts[4] = world.getLoadedChunks().length;
            result.put(world.getName(), counts);
        }
        return result;
    }

    public long nextSweepIn() {
        return sweepEnabled ? Math.max(0, nextSweep - System.currentTimeMillis()) : -1;
    }

    public long lastSweepTime() {
        return lastSweepTime;
    }

    public int lastSweepRemoved() {
        return lastSweepRemoved;
    }

    public long spawnsBlocked() {
        return spawnsBlocked;
    }

    private void broadcast(String key, Object... placeholders) {
        String message = plugin.messages().prefix() + plugin.messages().get(key, placeholders);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }

    private void notifyStaff(String key, Object... placeholders) {
        String message = plugin.messages().prefix() + plugin.messages().get(key, placeholders);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("servercore.lagguard.notify")) {
                player.sendMessage(message);
            }
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }
}
