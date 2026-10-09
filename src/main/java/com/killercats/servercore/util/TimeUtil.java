package com.killercats.servercore.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeUtil {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(w|d|h|m|s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FULL_DURATION = Pattern.compile("^((\\d+)\\s*(w|d|h|m|s)\\s*)+$", Pattern.CASE_INSENSITIVE);

    private TimeUtil() {
    }

    /**
     * Parses durations such as "1h30m", "2d", "45m" or "90s".
     *
     * @return milliseconds, or -1 if the text is not a duration
     */
    public static long parseDuration(String input) {
        if (input == null || !FULL_DURATION.matcher(input).matches()) {
            return -1;
        }
        Matcher matcher = DURATION.matcher(input);
        long total = 0;
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(1));
            switch (Character.toLowerCase(matcher.group(2).charAt(0))) {
                case 'w':
                    total += value * 7L * 24 * 60 * 60 * 1000;
                    break;
                case 'd':
                    total += value * 24L * 60 * 60 * 1000;
                    break;
                case 'h':
                    total += value * 60L * 60 * 1000;
                    break;
                case 'm':
                    total += value * 60L * 1000;
                    break;
                default:
                    total += value * 1000L;
                    break;
            }
        }
        return total;
    }

    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        long seconds = millis / 1000;
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m ");
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append("s");
        }
        return sb.toString().trim();
    }

    public static String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }
}
