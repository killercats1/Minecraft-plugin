package com.killercats.servercore.punish;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

/** One row of sc_punishments. */
public final class Punishment {

    public enum Type {
        WARN, KICK, MUTE, BAN;

        public static Type parse(String input) {
            if (input == null) {
                return null;
            }
            switch (input.toLowerCase(Locale.ROOT)) {
                case "warn":
                    return WARN;
                case "kick":
                    return KICK;
                case "mute":
                case "tempmute":
                    return MUTE;
                case "ban":
                case "tempban":
                    return BAN;
                default:
                    return null;
            }
        }
    }

    public final long id;
    public final Type type;
    public final UUID uuid;
    public final String name;
    public final String ip;
    public final String reason;
    public final String preset;
    public final String staffName;
    public final long created;
    /** 0 = permanent. */
    public final long expires;
    public final boolean active;
    public final String removedBy;

    public Punishment(long id, Type type, UUID uuid, String name, String ip, String reason, String preset, String staffName,
                      long created, long expires, boolean active, String removedBy) {
        this.id = id;
        this.type = type;
        this.uuid = uuid;
        this.name = name;
        this.ip = ip;
        this.reason = reason;
        this.preset = preset;
        this.staffName = staffName;
        this.created = created;
        this.expires = expires;
        this.active = active;
        this.removedBy = removedBy;
    }

    static Punishment read(ResultSet rs) throws SQLException {
        return new Punishment(rs.getLong("id"), Type.valueOf(rs.getString("type")), UUID.fromString(rs.getString("uuid")),
                rs.getString("name"), rs.getString("ip"), rs.getString("reason"), rs.getString("preset"), rs.getString("staff_name"),
                rs.getLong("created"), rs.getLong("expires"), rs.getInt("active") == 1, rs.getString("removed_by"));
    }

    public boolean permanent() {
        return expires <= 0;
    }

    /** Whether this ban/mute still applies right now. */
    public boolean inForce(long now) {
        return active && (type == Type.BAN || type == Type.MUTE) && (permanent() || expires > now);
    }
}
