package com.killercats.servercore.heads;

import java.util.UUID;

/** What the server knows about a player for building their head. */
final class HeadIdentity {

    final UUID uuid;
    volatile String name;
    volatile ClientType client;
    volatile String textures;
    volatile String signature;
    volatile long updated;

    HeadIdentity(UUID uuid, String name, ClientType client, String textures, String signature, long updated) {
        this.uuid = uuid;
        this.name = name;
        this.client = client;
        this.textures = textures;
        this.signature = signature;
        this.updated = updated;
    }
}
