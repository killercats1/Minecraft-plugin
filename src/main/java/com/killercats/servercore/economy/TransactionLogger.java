package com.killercats.servercore.economy;

import com.killercats.servercore.ServerCore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persists an audit trail of every money movement the plugin performs or observes. */
public final class TransactionLogger {

    public static final class Entry {
        public final long time;
        public final TransactionType type;
        public final double amount;
        public final String details;

        Entry(long time, TransactionType type, double amount, String details) {
            this.time = time;
            this.type = type;
            this.amount = amount;
            this.details = details;
        }
    }

    private final ServerCore plugin;
    private boolean enabled;

    public TransactionLogger(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("economy.transaction-log.enabled", true);
    }

    /**
     * @param amount signed amount from the player's point of view (negative = money left the player)
     */
    public void log(TransactionType type, UUID player, UUID other, double amount, String details) {
        if (!enabled || player == null) {
            return;
        }
        String trimmed = details == null ? "" : (details.length() > 255 ? details.substring(0, 252) + "..." : details);
        plugin.database().update("INSERT INTO sc_transactions (created, type, player, other, amount, details) VALUES (?, ?, ?, ?, ?, ?)",
                System.currentTimeMillis(), type.name(), player.toString(), other == null ? null : other.toString(), amount, trimmed);
    }

    public CompletableFuture<List<Entry>> history(UUID player, int page, int pageSize) {
        int offset = Math.max(0, page - 1) * pageSize;
        return plugin.database().query("SELECT created, type, amount, details FROM sc_transactions WHERE player = ? ORDER BY id DESC LIMIT "
                + pageSize + " OFFSET " + offset, rs -> {
            List<Entry> list = new ArrayList<Entry>();
            while (rs.next()) {
                TransactionType type;
                try {
                    type = TransactionType.valueOf(rs.getString("type"));
                } catch (IllegalArgumentException e) {
                    type = TransactionType.ADMIN;
                }
                list.add(new Entry(rs.getLong("created"), type, rs.getDouble("amount"), rs.getString("details")));
            }
            return list;
        }, player.toString());
    }

    /** Totals per transaction type during the given time window, used by /ecostats. */
    public CompletableFuture<List<String[]>> summary(long since) {
        return plugin.database().query("SELECT type, COUNT(*) AS c, SUM(amount) AS s FROM sc_transactions WHERE created >= ? GROUP BY type ORDER BY c DESC", rs -> {
            List<String[]> list = new ArrayList<String[]>();
            while (rs.next()) {
                list.add(new String[]{rs.getString("type"), String.valueOf(rs.getLong("c")), String.valueOf(rs.getDouble("s"))});
            }
            return list;
        }, since);
    }

    public CompletableFuture<Integer> purgeOlderThan(long time) {
        return plugin.database().update("DELETE FROM sc_transactions WHERE created < ?", time);
    }
}
