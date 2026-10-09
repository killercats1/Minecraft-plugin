package com.killercats.servercore.util;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class Sounds {

    private Sounds() {
    }

    /** Plays a sound by its 1.8 enum name, silently ignoring unknown names so configs can't break anything. */
    public static void play(Player player, String name) {
        if (player == null || name == null || name.isEmpty() || name.equalsIgnoreCase("none")) {
            return;
        }
        try {
            player.playSound(player.getLocation(), Sound.valueOf(name.toUpperCase()), 1F, 1F);
        } catch (IllegalArgumentException ignored) {
            // unknown sound name in config
        }
    }
}
