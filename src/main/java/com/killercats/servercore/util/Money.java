package com.killercats.servercore.util;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Money formatting and parsing. All amounts are rounded to two decimals so floating point
 * drift never leaks into balances.
 */
public final class Money {

    private static final String[] SUFFIXES = {"", "K", "M", "B", "T", "Q"};

    private static String symbol = "$";
    private static boolean symbolAfter = false;
    private static DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    private static double maxAmount = 1_000_000_000_000D;

    private Money() {
    }

    public static void configure(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        symbol = section.getString("currency-symbol", "$");
        symbolAfter = section.getBoolean("symbol-after-amount", false);
        format = new DecimalFormat(section.getString("number-format", "#,##0.00"), DecimalFormatSymbols.getInstance(Locale.US));
        maxAmount = section.getDouble("max-transaction", 1_000_000_000_000D);
    }

    public static double round(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return 0;
        }
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
    }

    public static String format(double amount) {
        String number = format.format(round(amount));
        return symbolAfter ? number + symbol : symbol + number;
    }

    /** Compact form such as $1.25M, used where space is limited (scoreboards, GUIs). */
    public static String shortFormat(double amount) {
        double abs = Math.abs(amount);
        int index = 0;
        while (abs >= 1000 && index < SUFFIXES.length - 1) {
            abs /= 1000;
            index++;
        }
        if (index == 0) {
            return format(amount);
        }
        String number = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US)).format(abs) + SUFFIXES[index];
        if (amount < 0) {
            number = "-" + number;
        }
        return symbolAfter ? number + symbol : symbol + number;
    }

    /**
     * Parses user input like "1500", "1,500.50", "2.5k", "3m", "1b".
     *
     * @return the parsed, rounded amount or -1 when the input is invalid, not positive or too large
     */
    public static double parse(String input) {
        if (input == null || input.isEmpty()) {
            return -1;
        }
        String value = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace(symbol.toLowerCase(Locale.ROOT), "");
        double multiplier = 1;
        if (!value.isEmpty()) {
            char last = value.charAt(value.length() - 1);
            switch (last) {
                case 'k':
                    multiplier = 1_000D;
                    break;
                case 'm':
                    multiplier = 1_000_000D;
                    break;
                case 'b':
                    multiplier = 1_000_000_000D;
                    break;
                case 't':
                    multiplier = 1_000_000_000_000D;
                    break;
                default:
                    break;
            }
            if (multiplier != 1) {
                value = value.substring(0, value.length() - 1);
            }
        }
        try {
            double amount = round(Double.parseDouble(value) * multiplier);
            if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0 || amount > maxAmount) {
                return -1;
            }
            return amount;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static double maxAmount() {
        return maxAmount;
    }

    public static String percent(double value) {
        return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US)).format(value) + "%";
    }
}
