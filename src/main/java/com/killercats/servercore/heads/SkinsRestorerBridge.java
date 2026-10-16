package com.killercats.servercore.heads;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Asks SkinsRestorer (15 or newer) which skin a player chose with /skin. Uses reflection, so
 * SkinsRestorer stays optional.
 *
 * Only a chosen skin is really the player's own: on an offline server SkinsRestorer also gives
 * players without one the skin of the premium account with the same name, or a default skin.
 */
final class SkinsRestorerBridge {

    /** The skin a player chose with /skin; textures == null means they chose none. */
    static final class Choice {
        static final Choice NONE = new Choice(null, null);

        final String textures;
        final String signature;

        Choice(String textures, String signature) {
            this.textures = textures;
            this.signature = signature;
        }

        boolean chosen() {
            return textures != null;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Choice)) {
                return false;
            }
            Choice other = (Choice) o;
            return textures == null ? other.textures == null : textures.equals(other.textures);
        }

        @Override
        public int hashCode() {
            return textures == null ? 0 : textures.hashCode();
        }
    }

    private final Plugin owner;
    private final Logger logger;
    private final Consumer<Player> onApply;
    private volatile Object storage;
    private volatile Method skinOfPlayer;
    private volatile Method value;
    private volatile Method signature;
    private Plugin hooked;
    private Plugin failed;
    private long retryAt;
    private boolean warned;

    /** @param onApply called (on any thread) when SkinsRestorer applies a skin to a player */
    SkinsRestorerBridge(Plugin owner, Consumer<Player> onApply) {
        this.owner = owner;
        this.logger = owner.getLogger();
        this.onApply = onApply;
    }

    /** True when SkinsRestorer is enabled and its API can be used. Main thread only. */
    boolean available() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("SkinsRestorer");
        if (plugin == null || !plugin.isEnabled()) {
            storage = null;
            hooked = null;
            return false;
        }
        if (storage != null && hooked == plugin) {
            return true;
        }
        if (failed == plugin && System.currentTimeMillis() < retryAt) {
            return false;
        }
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> provider = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider", true, loader);
            Class<?> api = Class.forName("net.skinsrestorer.api.SkinsRestorer", true, loader);
            Class<?> playerStorage = Class.forName("net.skinsrestorer.api.storage.PlayerStorage", true, loader);
            Class<?> property = Class.forName("net.skinsrestorer.api.property.SkinProperty", true, loader);
            Object instance = provider.getMethod("get").invoke(null);
            Object found = api.getMethod("getPlayerStorage").invoke(instance);
            skinOfPlayer = playerStorage.getMethod("getSkinOfPlayer", UUID.class);
            value = property.getMethod("getValue");
            signature = property.getMethod("getSignature");
            storage = found;
            hooked = plugin;
            subscribe(loader, api, instance);
            logger.info("Head fix: using SkinsRestorer, so skins chosen with /skin make heads permanent.");
            return true;
        } catch (Throwable t) {
            failed = plugin;
            retryAt = System.currentTimeMillis() + 60_000L;
            if (!warned) {
                warned = true;
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                logger.warning("Head fix: cannot read SkinsRestorer skins (needs SkinsRestorer 15 or newer): " + cause
                        + ". Eaglercraft players' heads keep showing their live skin.");
            }
            return false;
        }
    }

    /** Hears about skin changes right away (/skin, the /skins menu, admin commands). Optional. */
    private void subscribe(ClassLoader loader, Class<?> api, Object instance) {
        try {
            Class<?> bus = Class.forName("net.skinsrestorer.api.event.EventBus", true, loader);
            Class<?> applyEvent = Class.forName("net.skinsrestorer.api.event.SkinApplyEvent", true, loader);
            Method getPlayer = applyEvent.getMethod("getPlayer", Class.class);
            Consumer<Object> listener = event -> {
                try {
                    Object player = getPlayer.invoke(event, Player.class);
                    if (player instanceof Player) {
                        onApply.accept((Player) player);
                    }
                } catch (Throwable ignored) {
                    // never break SkinsRestorer
                }
            };
            Object eventBus = api.getMethod("getEventBus").invoke(instance);
            bus.getMethod("subscribe", Object.class, Class.class, Consumer.class).invoke(eventBus, owner, applyEvent, listener);
        } catch (Throwable t) {
            logger.info("Head fix: SkinsRestorer skin changes are picked up within a few minutes (" + t + ").");
        }
    }

    /**
     * The skin the player chose, {@link Choice#NONE} if none, or null if SkinsRestorer couldn't tell.
     * SkinsRestorer may read its storage here, so call this off the main thread.
     */
    Choice lookup(UUID uuid) {
        Object current = storage;
        if (current == null) {
            return null;
        }
        try {
            Optional<?> skin = (Optional<?>) skinOfPlayer.invoke(current, uuid);
            if (skin == null || !skin.isPresent()) {
                return Choice.NONE;
            }
            Object property = skin.get();
            String textures = (String) value.invoke(property);
            String sig = (String) signature.invoke(property);
            if (textures == null || textures.isEmpty()) {
                return Choice.NONE;
            }
            return new Choice(textures, sig == null || sig.isEmpty() ? null : sig);
        } catch (Throwable t) {
            return null;
        }
    }
}
