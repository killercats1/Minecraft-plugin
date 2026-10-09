package com.killercats.servercore.util;

import org.bukkit.entity.Player;

public final class Perms {

    private Perms() {
    }

    /**
     * True only if the permission was explicitly granted (directly, through a group or a wildcard).
     * Used for rank perks: plain hasPermission() is true for operators on every undeclared node,
     * which would give ops the highest multiplier/salary/limit of every rank.
     */
    public static boolean explicit(Player player, String permission) {
        return player.isPermissionSet(permission) && player.hasPermission(permission);
    }
}
