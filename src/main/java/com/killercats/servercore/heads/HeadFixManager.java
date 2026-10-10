package com.killercats.servercore.heads;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killercats.servercore.ServerCore;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.SkullType;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Skull;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes player heads work for EaglercraftX 1.8 players on an offline-mode server.
 *
 * Why heads break: the Eaglercraft client only draws a head's skin when the head's profile has a
 * UUID, and then fetches the skin either by UUID (when the profile has isEaglerPlayer) or by the
 * texture URL. On an offline-mode server Spigot fills a head's profile by asking Mojang for the
 * name, which fails for cracked names (no UUID, so Steve) or returns a stranger's premium skin.
 *
 * The fix: give every head that belongs to a known player that player's server UUID, a textures
 * property (their real one, or an empty placeholder so Spigot never asks Mojang) and, for players
 * whose skin only exists in their browser, isEaglerPlayer=true so Eaglercraft clients fetch their
 * live skin by UUID. New heads are fixed by pre-filling Spigot's head skin cache; existing heads
 * are repaired in inventories, containers, dropped items, item frames, armor stands and placed blocks.
 */
public final class HeadFixManager implements Listener, PluginMessageListener {

    /** Base64 of {"textures":{}}: a valid, empty textures property. */
    static final String PLACEHOLDER = "eyJ0ZXh0dXJlcyI6e319";
    private static final String BRAND_CHANNEL = "MC|Brand";

    enum SkinSource {
        AUTO, LIVE, URL
    }

    private final ServerCore plugin;
    private HeadReflection reflection;
    private HeadRegistry registry;
    private boolean active;
    private boolean brandRegistered;

    private final Map<UUID, ClientType> online = new ConcurrentHashMap<UUID, ClientType>();
    private final Set<UUID> brandEagler = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    private final LinkedHashSet<String> chunkQueue = new LinkedHashSet<String>();
    private final List<BukkitTask> tasks = new ArrayList<BukkitTask>();

    private long itemsRepaired;
    private long blocksRepaired;

    // settings
    private boolean enabled;
    private SkinSource skinSource = SkinSource.AUTO;
    private final Set<String> urlHosts = new HashSet<String>();
    private boolean seedCache;
    private int maxSeeded;
    private boolean refreshViewers;
    private boolean detectBrand;
    private boolean repairInventories;
    private boolean repairContainers;
    private boolean repairDropped;
    private boolean repairPlaced;
    private boolean repairFramesAndStands;
    private int chunksPerTick;
    private boolean texturedWithoutId;
    private boolean premiumNamesakes;
    private boolean logRepairs;

    public HeadFixManager(ServerCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection config() {
        return plugin.getConfig().getConfigurationSection("head-fix");
    }

    private void readSettings() {
        ConfigurationSection c = config();
        enabled = c != null && c.getBoolean("enabled", true);
        if (c == null) {
            return;
        }
        try {
            skinSource = SkinSource.valueOf(c.getString("skin-source", "auto").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("head-fix.skin-source must be auto, live or url; using auto.");
            skinSource = SkinSource.AUTO;
        }
        urlHosts.clear();
        for (String host : c.getStringList("url-hosts")) {
            urlHosts.add(host.toLowerCase(Locale.ROOT).trim());
        }
        if (urlHosts.isEmpty()) {
            urlHosts.add("textures.minecraft.net");
        }
        seedCache = c.getBoolean("seed-skin-cache", true);
        maxSeeded = Math.max(0, c.getInt("max-seeded", 1500));
        refreshViewers = c.getBoolean("refresh-viewers-on-join", true);
        detectBrand = c.getBoolean("detect-brand", true);
        repairInventories = c.getBoolean("repair.inventories", true);
        repairContainers = c.getBoolean("repair.containers", true);
        repairDropped = c.getBoolean("repair.dropped-items", true);
        repairPlaced = c.getBoolean("repair.placed-heads", true);
        repairFramesAndStands = c.getBoolean("repair.frames-and-stands", true);
        chunksPerTick = Math.max(1, c.getInt("repair.chunks-per-tick", 4));
        texturedWithoutId = c.getBoolean("repair.textured-without-id", true);
        premiumNamesakes = c.getBoolean("repair.premium-namesakes", false);
        logRepairs = c.getBoolean("log-repairs", false);
    }

    /** Called on enable and on /servercore reload. */
    public void start() {
        stopTasks();
        readSettings();
        if (!enabled) {
            active = false;
            return;
        }
        if (reflection == null) {
            reflection = new HeadReflection(plugin.getLogger());
        }
        if (!reflection.available()) {
            active = false;
            return;
        }
        ConfigurationSection c = config();
        if (registry == null) {
            registry = new HeadRegistry(plugin);
            registry.load(Math.max(1, c.getInt("remember-days", 90)), Math.max(10, c.getInt("max-remembered", 2000)));
        }
        active = true;
        if (detectBrand && !brandRegistered) {
            try {
                Bukkit.getMessenger().registerIncomingPluginChannel(plugin, BRAND_CHANNEL, this);
                brandRegistered = true;
            } catch (Exception e) {
                plugin.getLogger().warning("Head fix: cannot listen for client brands (" + e.getMessage() + ").");
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            snapshot(player);
        }
        seedAll();

        long resnapshot = Math.max(1, c.getLong("resnapshot-minutes", 5)) * 60L * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                snapshot(player);
            }
        }, resnapshot, resnapshot));
        long reseed = Math.max(1, c.getLong("reseed-minutes", 20)) * 60L * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::seedAll, reseed, reseed));
        if (repairInventories) {
            long sweep = Math.max(5, c.getLong("repair.sweep-seconds", 30)) * 20L;
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    repairPlayer(player, false);
                }
            }, sweep, sweep));
        }
        if (repairPlaced || repairFramesAndStands) {
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::processChunks, 1L, 1L));
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    queue(chunk);
                }
            }
        }
        plugin.getLogger().info("Head fix active (skin-source " + skinSource.name().toLowerCase(Locale.ROOT) + ", " + registry.size()
                + " players remembered, items " + (reflection.canEditItems() ? "on" : "off") + ", placed heads "
                + (reflection.canEditBlocks() ? "on" : "off") + ", cache seeding " + (reflection.canSeed() && seedCache ? "on" : "off") + ").");
    }

    public void shutdown() {
        stopTasks();
        if (brandRegistered) {
            try {
                Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, BRAND_CHANNEL, this);
            } catch (Exception ignored) {
                // server shutting down
            }
            brandRegistered = false;
        }
    }

    private void stopTasks() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        chunkQueue.clear();
    }

    public boolean isActive() {
        return active;
    }

    // ------------------------------------------------------------------ client detection

    private ClientType detect(Player player, HeadReflection.ProfileData live) {
        if (live != null && live.eaglerFlag != null) {
            // The Eaglercraft gateway adds isEaglerPlayer=true to Eaglercraft players (false for Java ones).
            return "true".equalsIgnoreCase(live.eaglerFlag.trim()) ? ClientType.EAGLER : ClientType.JAVA;
        }
        if (brandEagler.contains(player.getUniqueId())) {
            return ClientType.EAGLER;
        }
        return ClientType.UNKNOWN;
    }

    /** Best guess of a player's client for display ({client} placeholder, /headfix who). */
    public ClientType clientOf(Player player) {
        ClientType type = online.get(player.getUniqueId());
        if (type != null && type != ClientType.UNKNOWN) {
            return type;
        }
        if (registry != null) {
            HeadIdentity identity = registry.byUuid(player.getUniqueId());
            if (identity != null) {
                return identity.client;
            }
        }
        return ClientType.UNKNOWN;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!active || !BRAND_CHANNEL.equals(channel) || message == null) {
            return;
        }
        String brand = readBrand(message);
        if (brand != null && brand.toLowerCase(Locale.ROOT).startsWith("eagler") && brandEagler.add(player.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    snapshot(player);
                }
            });
        }
    }

    /** MC|Brand payload: VarInt length + UTF-8 string. */
    static String readBrand(byte[] data) {
        int length = 0;
        int shift = 0;
        int index = 0;
        while (index < data.length && index < 5) {
            int b = data[index++] & 0xFF;
            length |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) {
                if (length < 0 || index + length > data.length) {
                    return null;
                }
                return new String(data, index, length, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ registry / seeding

    /** Records an online player's current name, skin and client, and re-seeds their head if it changed. */
    void snapshot(Player player) {
        if (!active) {
            return;
        }
        HeadReflection.ProfileData live = reflection.readPlayer(player);
        ClientType detected = detect(player, live);
        String textures = live == null ? null : live.textures;
        String signature = live == null ? null : live.signature;
        if (PLACEHOLDER.equals(textures)) {
            textures = null;
            signature = null;
        }
        boolean changed = registry.update(player.getUniqueId(), player.getName(), detected, textures, signature);
        HeadIdentity identity = registry.byUuid(player.getUniqueId());
        online.put(player.getUniqueId(), identity == null ? detected : identity.client);
        if (changed && identity != null) {
            seed(identity);
        }
    }

    boolean wantsEaglerFlag(HeadIdentity identity) {
        switch (skinSource) {
            case LIVE:
                return true;
            case URL:
                return false;
            default:
                if (identity.client == ClientType.EAGLER) {
                    return true;
                }
                if (identity.client == ClientType.JAVA) {
                    return false;
                }
                return !usableUrl(identity.textures);
        }
    }

    /** The skin URL inside a textures property, or null. */
    static String skinUrl(String textures) {
        if (textures == null || textures.isEmpty()) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(textures.trim()), StandardCharsets.UTF_8);
            JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject tex = root.getAsJsonObject().getAsJsonObject("textures");
            if (tex == null || !tex.has("SKIN")) {
                return null;
            }
            JsonElement url = tex.getAsJsonObject("SKIN").get("url");
            return url == null || url.isJsonNull() ? null : url.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /** True if the skin URL is on a host the Eaglercraft gateway downloads from (default textures.minecraft.net). */
    boolean usableUrl(String textures) {
        String url = skinUrl(textures);
        if (url == null) {
            return false;
        }
        try {
            String host = new URI(url).getHost();
            return host != null && urlHosts.contains(host.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    Object build(HeadIdentity identity) {
        boolean real = identity.textures != null && !identity.textures.isEmpty();
        return reflection.newProfile(identity.uuid, identity.name, real ? identity.textures : PLACEHOLDER,
                real ? identity.signature : null, wantsEaglerFlag(identity) ? "true" : null);
    }

    private void seed(HeadIdentity identity) {
        if (!active || !seedCache || !reflection.canSeed() || identity.name == null) {
            return;
        }
        Object profile = build(identity);
        Set<String> keys = new LinkedHashSet<String>();
        keys.add(identity.name);
        keys.add(identity.name.toLowerCase());
        keys.add(identity.name.toLowerCase(Locale.ROOT));
        for (String key : keys) {
            reflection.seed(key, profile);
        }
    }

    /** Pre-fills Spigot's head skin cache so every head created by name gets a working profile instantly. */
    public void seedAll() {
        if (!active || !seedCache || !reflection.canSeed()) {
            return;
        }
        List<HeadIdentity> list = new ArrayList<HeadIdentity>(registry.all());
        list.sort((a, b) -> Long.compare(b.updated, a.updated));
        int count = 0;
        for (HeadIdentity identity : list) {
            if (count++ >= maxSeeded) {
                break;
            }
            seed(identity);
        }
    }

    // ------------------------------------------------------------------ repair rules

    /** Returns a replacement profile for a broken head, or null if the head should be left alone. */
    Object fixFor(HeadReflection.ProfileData head) {
        if (head == null) {
            return null;
        }
        String name = head.name;
        boolean hasName = name != null && !name.trim().isEmpty();
        if (hasName) {
            HeadIdentity identity = registry.byName(name);
            if (identity != null) {
                boolean sameId = head.id != null && head.id.equals(identity.uuid);
                if (head.id == null || (!sameId && premiumNamesakes)) {
                    return build(identity);
                }
                if (sameId) {
                    boolean wantFlag = wantsEaglerFlag(identity);
                    boolean hasFlag = head.eaglerFlag != null;
                    if (!head.hasTextures() || wantFlag != hasFlag) {
                        return build(identity);
                    }
                }
                return null;
            }
        }
        if (texturedWithoutId && hasName && head.id == null && head.hasTextures()) {
            // Decorative head without a UUID: Eaglercraft can't draw it at all. A stable UUID derived
            // from the texture keeps identical heads stackable and stops Spigot replacing the texture.
            UUID stable = UUID.nameUUIDFromBytes(("ServerCoreHead:" + head.textures).getBytes(StandardCharsets.UTF_8));
            return reflection.newProfile(stable, name, head.textures, head.signature, head.eaglerFlag);
        }
        return null;
    }

    static boolean isPlayerHead(ItemStack item) {
        return item != null && item.getType() == Material.SKULL_ITEM && item.getDurability() == 3;
    }

    HeadReflection.ProfileData readItem(ItemStack item) {
        if (!active || !isPlayerHead(item)) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta instanceof SkullMeta ? reflection.readItem((SkullMeta) meta) : null;
    }

    HeadReflection.ProfileData readBlock(Skull skull) {
        return active ? reflection.readBlock(skull) : null;
    }

    /** Repairs the stack in place. Returns true if it was changed. */
    boolean repairItem(ItemStack item) {
        if (!active || !isPlayerHead(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return false;
        }
        SkullMeta skull = (SkullMeta) meta;
        HeadReflection.ProfileData data = reflection.readItem(skull);
        Object fix = fixFor(data);
        if (fix == null || !reflection.writeItem(skull, fix)) {
            return false;
        }
        item.setItemMeta(skull);
        itemsRepaired++;
        if (logRepairs) {
            plugin.getLogger().info("Head fix: repaired head item of " + data.name);
        }
        return true;
    }

    /** Repairs every head in the inventory. Returns how many were changed. */
    int repairInventory(Inventory inventory) {
        int changed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (repairItem(contents[i])) {
                inventory.setItem(i, contents[i]);
                changed++;
            }
        }
        if (inventory instanceof PlayerInventory) {
            PlayerInventory player = (PlayerInventory) inventory;
            ItemStack[] armor = player.getArmorContents();
            boolean armorChanged = false;
            for (ItemStack piece : armor) {
                if (repairItem(piece)) {
                    armorChanged = true;
                    changed++;
                }
            }
            if (armorChanged) {
                player.setArmorContents(armor);
            }
        }
        return changed;
    }

    int repairPlayer(Player player, boolean includeEnderChest) {
        if (!active) {
            return 0;
        }
        int changed = repairInventory(player.getInventory());
        if (includeEnderChest) {
            changed += repairInventory(player.getEnderChest());
        }
        if (changed > 0) {
            player.updateInventory();
        }
        return changed;
    }

    boolean repairBlock(Skull skull) {
        if (!active || !repairPlaced || skull.getSkullType() != SkullType.PLAYER) {
            return false;
        }
        HeadReflection.ProfileData data = reflection.readBlock(skull);
        Object fix = fixFor(data);
        if (fix == null || !reflection.writeBlock(skull, fix)) {
            return false;
        }
        blocksRepaired++;
        if (logRepairs) {
            plugin.getLogger().info("Head fix: repaired placed head of " + data.name + " at " + skull.getLocation());
        }
        return true;
    }

    int repairChunk(Chunk chunk) {
        int changed = 0;
        if (repairPlaced && reflection.canEditBlocks()) {
            for (BlockState state : chunk.getTileEntities()) {
                if (state instanceof Skull && repairBlock((Skull) state)) {
                    changed++;
                }
            }
        }
        if (repairFramesAndStands || repairDropped) {
            for (Entity entity : chunk.getEntities()) {
                if (repairFramesAndStands && entity instanceof ItemFrame) {
                    ItemFrame frame = (ItemFrame) entity;
                    ItemStack item = frame.getItem();
                    if (repairItem(item)) {
                        frame.setItem(item);
                        changed++;
                    }
                } else if (repairFramesAndStands && entity instanceof ArmorStand) {
                    ArmorStand stand = (ArmorStand) entity;
                    ItemStack helmet = stand.getHelmet();
                    if (repairItem(helmet)) {
                        stand.setHelmet(helmet);
                        changed++;
                    }
                } else if (repairDropped && entity instanceof Item) {
                    Item drop = (Item) entity;
                    ItemStack stack = drop.getItemStack();
                    if (repairItem(stack)) {
                        drop.setItemStack(stack);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private static String key(World world, int x, int z) {
        return world.getName() + '\u0000' + x + '\u0000' + z;
    }

    void queue(Chunk chunk) {
        if (active && (repairPlaced || repairFramesAndStands)) {
            chunkQueue.add(key(chunk.getWorld(), chunk.getX(), chunk.getZ()));
        }
    }

    /** Queues the chunks around a player; returns how many were queued. */
    int queueArea(Player player, int radius) {
        Chunk center = player.getLocation().getChunk();
        int count = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (player.getWorld().isChunkLoaded(center.getX() + x, center.getZ() + z)) {
                    chunkQueue.add(key(player.getWorld(), center.getX() + x, center.getZ() + z));
                    count++;
                }
            }
        }
        return count;
    }

    private void processChunks() {
        Iterator<String> iterator = chunkQueue.iterator();
        int done = 0;
        while (iterator.hasNext() && done < chunksPerTick) {
            String key = iterator.next();
            iterator.remove();
            String[] parts = key.split("\u0000");
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                continue;
            }
            int x = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            if (!world.isChunkLoaded(x, z)) {
                continue;
            }
            repairChunk(world.getChunkAt(x, z));
            done++;
        }
    }

    // ------------------------------------------------------------------ triggers

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!active) {
            return;
        }
        Player player = event.getPlayer();
        snapshot(player);
        // SkinsRestorer and similar plugins apply skins a little after join.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                snapshot(player);
            }
        }, 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                snapshot(player);
            }
        }, 200L);
        if (repairInventories) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    repairPlayer(player, true);
                }
            }, 60L);
        }
        if (refreshViewers) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> refreshViewers(player), 20L);
        }
    }

    /**
     * An Eaglercraft client that drew this player's head while they were offline cached "no skin"
     * for their UUID, and only forgets it when the player leaves the tab list. Re-sending the
     * player to Eaglercraft viewers clears that, so their body shows the right skin.
     */
    private void refreshViewers(Player player) {
        if (!active || !player.isOnline()) {
            return;
        }
        HeadIdentity identity = registry.byUuid(player.getUniqueId());
        if (identity == null || !wantsEaglerFlag(identity)) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player) || !viewer.canSee(player) || clientOf(viewer) == ClientType.JAVA) {
                continue;
            }
            viewer.hidePlayer(player);
            viewer.showPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (active) {
            snapshot(event.getPlayer());
        }
        online.remove(event.getPlayer().getUniqueId());
        brandEagler.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSkinCommand(PlayerCommandPreprocessEvent event) {
        if (!active) {
            return;
        }
        String label = event.getMessage().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.endsWith("skin") || label.endsWith("skins")) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    snapshot(player);
                }
            }, 40L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!active || !repairContainers) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        // Only real containers in the world; other plugins' menus are never touched.
        if (holder instanceof BlockState || holder instanceof DoubleChest) {
            repairInventory(event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!active || !repairDropped) {
            return;
        }
        ItemStack stack = event.getEntity().getItemStack();
        if (repairItem(stack)) {
            event.getEntity().setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        if (!active || !repairDropped) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (repairItem(stack)) {
            event.getItem().setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (active && !event.isNewChunk()) {
            queue(event.getChunk());
        }
    }

    // ------------------------------------------------------------------ for /headfix

    HeadRegistry registry() {
        return registry;
    }

    HeadReflection reflection() {
        return reflection;
    }

    SkinSource skinSource() {
        return skinSource;
    }

    boolean seeding() {
        return active && seedCache && reflection.canSeed();
    }

    long itemsRepaired() {
        return itemsRepaired;
    }

    long blocksRepaired() {
        return blocksRepaired;
    }

    int queuedChunks() {
        return chunkQueue.size();
    }

    boolean isUrlHostAllowed(String textures) {
        return usableUrl(textures);
    }

    /** A fresh, fixed head for a known or online player (null if unknown). */
    ItemStack headOf(String name, int amount) {
        if (!active) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            snapshot(online);
        }
        HeadIdentity identity = online != null ? registry.byUuid(online.getUniqueId()) : registry.byName(name);
        if (identity == null) {
            return null;
        }
        ItemStack item = new ItemStack(Material.SKULL_ITEM, Math.max(1, Math.min(64, amount)), (short) 3);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (!reflection.writeItem(meta, build(identity))) {
            meta.setOwner(identity.name);
        }
        item.setItemMeta(meta);
        return item;
    }
}
