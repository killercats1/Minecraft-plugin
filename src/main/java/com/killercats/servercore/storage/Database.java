package com.killercats.servercore.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite / MySQL storage. Every statement runs on one dedicated thread which owns the single
 * connection, so writes are applied strictly in the order they were submitted and the main
 * server thread never blocks on I/O. Both JDBC drivers ship with Spigot 1.8.8.
 */
public final class Database {

    public interface Mapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final JavaPlugin plugin;
    private final ExecutorService executor;
    private boolean mysql;
    private String url;
    private String user;
    private String password;
    private File sqliteFile;
    private Connection connection;

    public Database(JavaPlugin plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ServerCore-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void connect(ConfigurationSection section) throws Exception {
        mysql = section != null && "mysql".equalsIgnoreCase(section.getString("type", "sqlite"));
        if (mysql) {
            Class.forName("com.mysql.jdbc.Driver");
            url = "jdbc:mysql://" + section.getString("mysql.host", "localhost") + ":" + section.getInt("mysql.port", 3306)
                    + "/" + section.getString("mysql.database", "minecraft")
                    + "?useSSL=" + section.getBoolean("mysql.use-ssl", false) + "&autoReconnect=true&characterEncoding=utf8";
            user = section.getString("mysql.username", "root");
            password = section.getString("mysql.password", "");
        } else {
            Class.forName("org.sqlite.JDBC");
            sqliteFile = new File(plugin.getDataFolder(), section == null ? "data.db" : section.getString("sqlite-file", "data.db"));
            url = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
        }
        // Open the connection and create tables on the database thread, but wait for it.
        submit(() -> {
            createTables();
            return null;
        }).get(30, TimeUnit.SECONDS);
    }

    public boolean isMySql() {
        return mysql;
    }

    private Connection connection() throws SQLException {
        boolean valid;
        try {
            valid = connection != null && !connection.isClosed() && (!mysql || connection.isValid(2));
        } catch (SQLException e) {
            valid = false;
        }
        if (!valid) {
            connection = mysql ? DriverManager.getConnection(url, user, password) : DriverManager.getConnection(url);
        }
        return connection;
    }

    private void createTables() throws SQLException {
        String autoId = mysql ? "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String text = mysql ? "MEDIUMTEXT" : "TEXT";
        try (Statement st = connection().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sc_players ("
                    + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "name VARCHAR(16),"
                    + "bank_balance DOUBLE NOT NULL DEFAULT 0,"
                    + "bank_tier INT NOT NULL DEFAULT 0,"
                    + "last_daily BIGINT NOT NULL DEFAULT 0,"
                    + "daily_streak INT NOT NULL DEFAULT 0,"
                    + "accept_pay INT NOT NULL DEFAULT 1,"
                    + "scoreboard INT NOT NULL DEFAULT 1,"
                    + "payday_minutes INT NOT NULL DEFAULT 0,"
                    + "last_seen BIGINT NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sc_transactions ("
                    + "id " + autoId + ","
                    + "created BIGINT NOT NULL,"
                    + "type VARCHAR(32) NOT NULL,"
                    + "player VARCHAR(36) NOT NULL,"
                    + "other VARCHAR(36),"
                    + "amount DOUBLE NOT NULL,"
                    + "details VARCHAR(255)"
                    + (mysql ? ", INDEX idx_sc_tx_player (player, created)" : "")
                    + ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sc_banknotes ("
                    + "id VARCHAR(24) NOT NULL PRIMARY KEY,"
                    + "amount DOUBLE NOT NULL,"
                    + "creator VARCHAR(36) NOT NULL,"
                    + "created BIGINT NOT NULL,"
                    + "redeemed INT NOT NULL DEFAULT 0,"
                    + "redeemed_by VARCHAR(36),"
                    + "redeemed_at BIGINT NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sc_auctions ("
                    + "id BIGINT NOT NULL PRIMARY KEY,"
                    + "seller VARCHAR(36) NOT NULL,"
                    + "seller_name VARCHAR(16),"
                    + "item " + text + " NOT NULL,"
                    + "price DOUBLE NOT NULL,"
                    + "created BIGINT NOT NULL,"
                    + "expires BIGINT NOT NULL,"
                    + "state VARCHAR(16) NOT NULL,"
                    + "buyer VARCHAR(36),"
                    + "collected INT NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sc_bounties ("
                    + "target VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "target_name VARCHAR(16),"
                    + "amount DOUBLE NOT NULL,"
                    + "contributors " + text + ","
                    + "updated BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sc_link_rewards ("
                    + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "discord_id VARCHAR(32) NOT NULL UNIQUE,"
                    + "name VARCHAR(16),"
                    + "rewarded_at BIGINT NOT NULL)");
            if (!mysql) {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sc_tx_player ON sc_transactions (player, created)");
            }
        }
    }

    private interface SqlTask<T> {
        T run() throws SQLException;
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.run());
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE, "Database error", t);
                    future.completeExceptionally(t);
                }
            });
        } catch (Exception rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Runs an INSERT/UPDATE/DELETE and returns the number of affected rows. */
    public CompletableFuture<Integer> update(String sql, Object... params) {
        return submit(() -> {
            try (PreparedStatement ps = connection().prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    public <T> CompletableFuture<T> query(String sql, Mapper<T> mapper, Object... params) {
        return submit(() -> {
            try (PreparedStatement ps = connection().prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapper.map(rs);
                }
            }
        });
    }

    /**
     * Builds an "insert or update" statement for the active SQL dialect.
     * The first column must be the primary key.
     */
    public String upsert(String table, String... columns) {
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                cols.append(',');
                marks.append(',');
            }
            cols.append(columns[i]);
            marks.append('?');
        }
        if (!mysql) {
            return "INSERT OR REPLACE INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
        }
        StringBuilder update = new StringBuilder();
        for (int i = 1; i < columns.length; i++) {
            if (update.length() > 0) {
                update.append(',');
            }
            update.append(columns[i]).append("=VALUES(").append(columns[i]).append(')');
        }
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON DUPLICATE KEY UPDATE " + update;
    }

    /** Name of the SQLite file (empty for MySQL). */
    public String fileName() {
        return sqliteFile == null ? "" : sqliteFile.getName();
    }

    /**
     * Copies the SQLite file on the database thread, so no statement runs during the copy.
     * Completes with false for MySQL (back that up with mysqldump instead).
     */
    public CompletableFuture<Boolean> snapshot(File target) {
        if (mysql || sqliteFile == null) {
            return CompletableFuture.completedFuture(false);
        }
        return submit(() -> {
            try {
                java.nio.file.Files.copy(sqliteFile.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (java.io.IOException e) {
                throw new SQLException("Could not copy database", e);
            }
        });
    }

    /** Waits for every queued statement, then closes the connection. */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Database queue did not drain in time; some data may not be saved.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // closing anyway
        }
    }
}
