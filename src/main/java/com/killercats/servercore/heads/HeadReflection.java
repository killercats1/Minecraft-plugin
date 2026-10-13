package com.killercats.servercore.heads;

import com.google.common.cache.Cache;
import com.google.common.collect.Multimap;
import org.bukkit.Bukkit;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection access to Mojang's authlib GameProfile and CraftBukkit/NMS skull internals (v1_8_R3).
 * authlib is not on the compile classpath, so profiles are handled as plain Objects. Every target is
 * resolved once; a missing target only switches off the capability that needs it.
 */
final class HeadReflection {

    /** Read-only view of a GameProfile. */
    static final class ProfileData {
        final UUID id;
        final String name;
        final String textures;
        final String signature;
        final String eaglerFlag;

        ProfileData(UUID id, String name, String textures, String signature, String eaglerFlag) {
            this.id = id;
            this.name = name;
            this.textures = textures;
            this.signature = signature;
            this.eaglerFlag = eaglerFlag;
        }

        boolean hasTextures() {
            return textures != null && !textures.isEmpty();
        }
    }

    private boolean available;
    private Constructor<?> profileCtor;
    private Constructor<?> propertyCtor2;
    private Constructor<?> propertyCtor3;
    private Method profileGetId;
    private Method profileGetName;
    private Method profileGetProperties;
    private Method propertyGetValue;
    private Method propertyGetSignature;
    private Class<?> profileClass;

    private Field metaSkullProfile;
    private Class<?> metaSkullClass;
    private Method craftSkullGetTileEntity;
    private Method tileGetGameProfile;
    private Method tileSetGameProfile;
    private Cache<String, Object> skinCache;

    HeadReflection(Logger logger) {
        String craft = Bukkit.getServer().getClass().getPackage().getName();
        String version = craft.substring(craft.lastIndexOf('.') + 1);
        try {
            profileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            profileCtor = profileClass.getConstructor(UUID.class, String.class);
            propertyCtor2 = propertyClass.getConstructor(String.class, String.class);
            propertyCtor3 = propertyClass.getConstructor(String.class, String.class, String.class);
            profileGetId = profileClass.getMethod("getId");
            profileGetName = profileClass.getMethod("getName");
            profileGetProperties = profileClass.getMethod("getProperties");
            propertyGetValue = propertyClass.getMethod("getValue");
            propertyGetSignature = propertyClass.getMethod("getSignature");
            available = true;
        } catch (Exception | LinkageError e) {
            logger.warning("Head fix: Mojang authlib not found (" + e + "), the head fix is disabled.");
            return;
        }
        try {
            metaSkullClass = Class.forName(craft + ".inventory.CraftMetaSkull");
            metaSkullProfile = metaSkullClass.getDeclaredField("profile");
            metaSkullProfile.setAccessible(true);
        } catch (Exception | LinkageError e) {
            metaSkullProfile = null;
            logger.warning("Head fix: cannot edit head items directly (" + e + ").");
        }
        try {
            Class<?> craftSkull = Class.forName(craft + ".block.CraftSkull");
            craftSkullGetTileEntity = craftSkull.getMethod("getTileEntity");
            Class<?> tile = Class.forName("net.minecraft.server." + version + ".TileEntitySkull");
            tileGetGameProfile = tile.getMethod("getGameProfile");
            tileSetGameProfile = tile.getMethod("setGameProfile", profileClass);
        } catch (Exception | LinkageError e) {
            craftSkullGetTileEntity = null;
            logger.warning("Head fix: cannot edit placed heads (" + e + ").");
        }
        try {
            Class<?> tile = Class.forName("net.minecraft.server." + version + ".TileEntitySkull");
            Field cache = tile.getField("skinCache");
            @SuppressWarnings("unchecked")
            Cache<String, Object> value = (Cache<String, Object>) cache.get(null);
            skinCache = value;
        } catch (Exception | LinkageError e) {
            skinCache = null;
            logger.warning("Head fix: Spigot's head skin cache is not available (" + e + "); only repairs will run.");
        }
    }

    boolean available() {
        return available;
    }

    boolean canEditItems() {
        return available && metaSkullProfile != null;
    }

    boolean canEditBlocks() {
        return available && craftSkullGetTileEntity != null;
    }

    boolean canSeed() {
        return available && skinCache != null;
    }

    /** Builds a NEW GameProfile; textures/eaglerFlag may be null. */
    Object newProfile(UUID id, String name, String textures, String signature, String eaglerFlag) {
        try {
            Object profile = profileCtor.newInstance(id, name);
            Multimap<String, Object> properties = properties(profile);
            if (textures != null && !textures.isEmpty()) {
                properties.put("textures", signature == null || signature.isEmpty()
                        ? propertyCtor2.newInstance("textures", textures)
                        : propertyCtor3.newInstance("textures", textures, signature));
            }
            if (eaglerFlag != null) {
                properties.put("isEaglerPlayer", propertyCtor2.newInstance("isEaglerPlayer", eaglerFlag));
            }
            return profile;
        } catch (Exception e) {
            throw new IllegalStateException("Could not build a GameProfile", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Multimap<String, Object> properties(Object profile) throws Exception {
        return (Multimap<String, Object>) profileGetProperties.invoke(profile);
    }

    /** Reads a GameProfile (null-safe). Never modifies it. */
    ProfileData read(Object profile) {
        if (profile == null || !profileClass.isInstance(profile)) {
            return null;
        }
        try {
            UUID id = (UUID) profileGetId.invoke(profile);
            String name = (String) profileGetName.invoke(profile);
            Multimap<String, Object> properties = properties(profile);
            String textures = null;
            String signature = null;
            String flag = null;
            Collection<Object> tex = properties.get("textures");
            if (tex != null && !tex.isEmpty()) {
                Object first = tex.iterator().next();
                textures = (String) propertyGetValue.invoke(first);
                signature = (String) propertyGetSignature.invoke(first);
            }
            Collection<Object> eagler = properties.get("isEaglerPlayer");
            if (eagler != null && !eagler.isEmpty()) {
                flag = (String) propertyGetValue.invoke(eagler.iterator().next());
            }
            return new ProfileData(id, name, textures, signature, flag);
        } catch (Exception e) {
            return null;
        }
    }

    /** The live profile of an online player (read only). */
    ProfileData readPlayer(Player player) {
        try {
            Method getProfile = player.getClass().getMethod("getProfile");
            return read(getProfile.invoke(player));
        } catch (Exception e) {
            return null;
        }
    }

    ProfileData readItem(SkullMeta meta) {
        if (!canEditItems() || !metaSkullClass.isInstance(meta)) {
            return null;
        }
        try {
            return read(metaSkullProfile.get(meta));
        } catch (Exception e) {
            return null;
        }
    }

    boolean writeItem(SkullMeta meta, Object profile) {
        if (!canEditItems() || !metaSkullClass.isInstance(meta)) {
            return false;
        }
        try {
            metaSkullProfile.set(meta, profile);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Object tile(Skull skull) throws Exception {
        return craftSkullGetTileEntity.invoke(skull);
    }

    ProfileData readBlock(Skull skull) {
        if (!canEditBlocks()) {
            return null;
        }
        try {
            Object tile = tile(skull);
            return tile == null ? null : read(tileGetGameProfile.invoke(tile));
        } catch (Exception e) {
            return null;
        }
    }

    /** Sets the profile on the live tile entity; Spigot then re-sends the head to nearby players. */
    boolean writeBlock(Skull skull, Object profile) {
        if (!canEditBlocks()) {
            return false;
        }
        try {
            Object tile = tile(skull);
            if (tile == null) {
                return false;
            }
            tileSetGameProfile.invoke(tile, profile);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void seed(String key, Object profile) {
        if (skinCache != null && key != null && !key.isEmpty()) {
            skinCache.put(key, profile);
        }
    }

    long seededSize() {
        return skinCache == null ? -1 : skinCache.size();
    }
}
