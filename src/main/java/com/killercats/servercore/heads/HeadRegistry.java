package com.killercats.servercore.heads;

import com.killercats.servercore.ServerCore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Players the head fix knows about, persisted in sc_head_profiles. Names from sc_players are added
 * too, so heads of players who joined before this feature existed are fixed as well.
 */
final class HeadRegistry {

    private static final String[] COLUMNS = {"uuid", "name", "client", "textures", "signature", "updated"};

    private final ServerCore plugin;
    private final String upsertSql;
    private final Map<UUID, HeadIdentity> byUuid = new ConcurrentHashMap<UUID, HeadIdentity>();
    private final Map<String, HeadIdentity> byName = new ConcurrentHashMap<String, HeadIdentity>();

    HeadRegistry(ServerCore plugin) {
        this.plugin = plugin;
        this.upsertSql = plugin.database().upsert("sc_head_profiles", COLUMNS);
    }

    /** Loads remembered players; blocks for at most a few seconds so heads are seeded before anyone joins. */
    void load(int rememberDays, int maxRemembered) {
        long since = System.currentTimeMillis() - rememberDays * 24L * 60 * 60 * 1000;
        try {
            List<HeadIdentity> stored = plugin.database().query("SELECT uuid, name, client, textures, signature, updated FROM sc_head_profiles"
                    + " WHERE updated >= ? ORDER BY updated DESC LIMIT " + maxRemembered, rs -> {
                List<HeadIdentity> list = new ArrayList<HeadIdentity>();
                while (rs.next()) {
                    try {
                        list.add(new HeadIdentity(UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                                ClientType.fromId(rs.getInt("client")), rs.getString("textures"), rs.getString("signature"), rs.getLong("updated")));
                    } catch (IllegalArgumentException ignored) {
                        // bad uuid
                    }
                }
                return list;
            }, since).get(3, TimeUnit.SECONDS);
            for (HeadIdentity identity : stored) {
                add(identity);
            }
            int room = Math.max(0, maxRemembered - byUuid.size());
            if (room > 0) {
                List<HeadIdentity> players = plugin.database().query("SELECT uuid, name, last_seen FROM sc_players WHERE name IS NOT NULL"
                        + " AND last_seen >= ? ORDER BY last_seen DESC LIMIT " + maxRemembered, rs -> {
                    List<HeadIdentity> list = new ArrayList<HeadIdentity>();
                    while (rs.next()) {
                        try {
                            list.add(new HeadIdentity(UUID.fromString(rs.getString("uuid")), rs.getString("name"), ClientType.UNKNOWN,
                                    null, null, rs.getLong("last_seen")));
                        } catch (IllegalArgumentException ignored) {
                            // bad uuid
                        }
                    }
                    return list;
                }, since).get(3, TimeUnit.SECONDS);
                for (HeadIdentity identity : players) {
                    if (room <= 0) {
                        break;
                    }
                    if (!byUuid.containsKey(identity.uuid)) {
                        add(identity);
                        room--;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Head fix: could not load remembered players: " + e.getMessage());
        }
    }

    private void add(HeadIdentity identity) {
        if (identity.name == null || identity.name.isEmpty()) {
            return;
        }
        byUuid.put(identity.uuid, identity);
        String key = identity.name.toLowerCase(Locale.ROOT);
        HeadIdentity existing = byName.get(key);
        // Case-only or reused names: the most recently seen player wins.
        if (existing == null || existing.updated <= identity.updated) {
            byName.put(key, identity);
        }
    }

    HeadIdentity byName(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    HeadIdentity byUuid(UUID uuid) {
        return uuid == null ? null : byUuid.get(uuid);
    }

    Collection<HeadIdentity> all() {
        return byUuid.values();
    }

    int size() {
        return byUuid.size();
    }

    /**
     * Records the latest information about an online player. Returns the identity and whether
     * anything changed (so callers can re-seed only when needed).
     */
    boolean update(UUID uuid, String name, ClientType client, String textures, String signature) {
        HeadIdentity identity = byUuid.get(uuid);
        boolean changed;
        if (identity == null) {
            identity = new HeadIdentity(uuid, name, client, textures, signature, System.currentTimeMillis());
            changed = true;
        } else {
            ClientType newClient = client == ClientType.UNKNOWN ? identity.client : client;
            // Also re-save once a day so regularly playing players never age out of remember-days.
            boolean stale = System.currentTimeMillis() - identity.updated > 24L * 60 * 60 * 1000;
            changed = stale || !name.equals(identity.name) || newClient != identity.client || !same(textures, identity.textures)
                    || !same(signature, identity.signature);
            if (changed && !name.equalsIgnoreCase(identity.name)) {
                byName.remove(identity.name.toLowerCase(Locale.ROOT), identity);
            }
            identity.name = name;
            identity.client = newClient;
            identity.textures = textures;
            identity.signature = signature;
            identity.updated = System.currentTimeMillis();
        }
        add(identity);
        if (changed) {
            save(identity);
        }
        return changed;
    }

    void save(HeadIdentity identity) {
        plugin.database().update(upsertSql, identity.uuid.toString(), identity.name, identity.client.id(), identity.textures,
                identity.signature, identity.updated);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
