package com.killercats.servercore.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public final class Text {

    private Text() {
    }

    public static String color(String input) {
        return input == null ? "" : ChatColor.translateAlternateColorCodes('&', input);
    }

    public static List<String> color(List<String> input) {
        List<String> out = new ArrayList<String>(input.size());
        for (String line : input) {
            out.add(color(line));
        }
        return out;
    }

    public static String strip(String input) {
        return input == null ? "" : ChatColor.stripColor(input);
    }

    /**
     * Cuts a (colored) string to a maximum length without leaving a dangling color character.
     * 1.8 has hard limits on inventory titles (32) and scoreboard prefixes (16).
     */
    public static String trim(String input, int max) {
        if (input == null) {
            return "";
        }
        if (input.length() <= max) {
            return input;
        }
        String cut = input.substring(0, max);
        if (cut.endsWith(String.valueOf(ChatColor.COLOR_CHAR))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

    public static String replace(String message, Object... placeholders) {
        if (message == null || placeholders == null) {
            return message;
        }
        String out = message;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            out = out.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return out;
    }

    public static String capitalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String[] words = input.toLowerCase().replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    public static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    public static String progressBar(double fraction, int length, String full, String empty) {
        fraction = Math.max(0, Math.min(1, fraction));
        int filled = (int) Math.round(fraction * length);
        StringBuilder sb = new StringBuilder();
        sb.append(full);
        for (int i = 0; i < filled; i++) {
            sb.append('|');
        }
        sb.append(empty);
        for (int i = filled; i < length; i++) {
            sb.append('|');
        }
        return color(sb.toString());
    }
}
