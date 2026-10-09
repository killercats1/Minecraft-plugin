package com.killercats.servercore.features;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Scheduled and manual zip backups of the worlds, important plugin data and the server's player
 * lists. Worlds are saved and auto-save is paused while the zip is written on a background thread,
 * so the copy is consistent without freezing the server.
 */
public final class BackupManager {

    private static final String[] ROOT_FILES = {"server.properties", "ops.json", "whitelist.json", "banned-players.json",
            "banned-ips.json", "bukkit.yml", "spigot.yml", "paper.yml"};

    private final ServerCore plugin;
    private BukkitTask task;
    private volatile boolean running;

    public BackupManager(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!plugin.getConfig().getBoolean("backups.enabled", true)) {
            return;
        }
        long ticks = (long) (Math.max(1, plugin.getConfig().getDouble("backups.interval-hours", 12)) * 60 * 60 * 20);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> start(null), ticks, ticks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }

    public boolean isRunning() {
        return running;
    }

    private File folder() {
        return new File(Bukkit.getWorldContainer(), plugin.getConfig().getString("backups.folder", "backups"));
    }

    /** Starts a backup; the requester (may be null) is told when it is finished. Must be called on the main thread. */
    public void start(CommandSender requester) {
        if (running) {
            if (requester != null) {
                plugin.messages().send(requester, "backup.already-running");
            }
            return;
        }
        running = true;
        long started = System.currentTimeMillis();
        broadcastStaff("backup.started");
        if (requester != null) {
            plugin.messages().send(requester, "backup.started");
        }

        // Flush everything to disk and stop the server from writing region files while we read them.
        Bukkit.savePlayers();
        plugin.players().saveDirty();
        final Map<World, Boolean> autoSave = new HashMap<World, Boolean>();
        final List<File> worldFolders = new ArrayList<File>();
        for (World world : Bukkit.getWorlds()) {
            world.save();
            autoSave.put(world, world.isAutoSave());
            world.setAutoSave(false);
            worldFolders.add(world.getWorldFolder());
        }
        final File pluginsDir = plugin.getDataFolder().getParentFile();
        final List<String> pluginNames = plugin.getConfig().getStringList("backups.include-plugins");
        final File target = new File(folder(), "backup-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date()) + ".zip");

        Thread thread = new Thread(() -> {
            String error = null;
            long size = 0;
            File dbCopy = null;
            try {
                File dir = folder();
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("Cannot create backup folder " + dir);
                }
                long estimate = 0;
                for (File world : worldFolders) {
                    estimate += sizeOf(world);
                }
                for (String name : pluginNames) {
                    estimate += sizeOf(new File(pluginsDir, name));
                }
                // Zips are usually much smaller than the data, but never fill the disk completely.
                if (dir.getUsableSpace() < estimate / 2 + 50L * 1024 * 1024) {
                    throw new IOException("Not enough free disk space (need about " + (estimate / 2 / 1024 / 1024 + 50) + " MB)");
                }
                // Copy ServerCore's SQLite file on the database thread so no write happens mid-copy.
                dbCopy = new File(dir, ".servercore-db.tmp");
                boolean haveDb = plugin.database().snapshot(dbCopy).get(60, TimeUnit.SECONDS);

                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
                    zip.setLevel(Deflater.BEST_SPEED);
                    for (File world : worldFolders) {
                        addTree(zip, world, world.getName(), dir);
                    }
                    for (String name : pluginNames) {
                        File folder = new File(pluginsDir, name);
                        if (name.equalsIgnoreCase(plugin.getName())) {
                            addTree(zip, folder, "plugins/" + folder.getName(), dir, plugin.database().fileName());
                            if (haveDb) {
                                addFile(zip, dbCopy, "plugins/" + folder.getName() + "/" + plugin.database().fileName());
                            }
                        } else {
                            addTree(zip, folder, "plugins/" + folder.getName(), dir);
                        }
                    }
                    for (String name : ROOT_FILES) {
                        File file = new File(Bukkit.getWorldContainer(), name);
                        if (file.isFile()) {
                            addFile(zip, file, name);
                        }
                    }
                }
                size = target.length();
                pruneOld(dir);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                plugin.getLogger().log(Level.WARNING, "Backup failed", e);
                if (target.exists() && !target.delete()) {
                    target.deleteOnExit();
                }
            } finally {
                if (dbCopy != null && dbCopy.exists() && !dbCopy.delete()) {
                    dbCopy.deleteOnExit();
                }
            }
            final String failure = error;
            final long finalSize = size;
            Runnable finish = () -> {
                for (Map.Entry<World, Boolean> entry : autoSave.entrySet()) {
                    entry.getKey().setAutoSave(entry.getValue());
                }
                running = false;
                String time = TimeUtil.formatDuration(System.currentTimeMillis() - started);
                if (failure == null) {
                    plugin.getLogger().info("Backup saved: " + target.getName() + " (" + finalSize / 1024 / 1024 + " MB, " + time + ")");
                    broadcastStaff("backup.finished", "file", target.getName(), "size", finalSize / 1024 / 1024, "time", time);
                    if (requester != null) {
                        plugin.messages().send(requester, "backup.finished", "file", target.getName(), "size", finalSize / 1024 / 1024, "time", time);
                    }
                } else {
                    broadcastStaff("backup.failed", "error", failure);
                    if (requester != null) {
                        plugin.messages().send(requester, "backup.failed", "error", failure);
                    }
                }
            };
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, finish);
            } else {
                running = false;
            }
        }, "ServerCore-Backup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void pruneOld(File dir) {
        int keep = Math.max(1, plugin.getConfig().getInt("backups.keep", 2));
        File[] files = dir.listFiles((d, name) -> name.startsWith("backup-") && name.endsWith(".zip"));
        if (files == null || files.length <= keep) {
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < files.length; i++) {
            if (!files[i].delete()) {
                plugin.getLogger().warning("Could not delete old backup " + files[i].getName());
            }
        }
    }

    public List<String> list() {
        List<String> out = new ArrayList<String>();
        File[] files = folder().listFiles((d, name) -> name.startsWith("backup-") && name.endsWith(".zip"));
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files) {
                out.add(file.getName() + " (" + file.length() / 1024 / 1024 + " MB)");
            }
        }
        return out;
    }

    private static long sizeOf(File file) {
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }
        return total;
    }

    private void addTree(ZipOutputStream zip, File file, String path, File backupDir, String... skipNames) throws IOException {
        if (!file.exists() || file.equals(backupDir)) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                addTree(zip, child, path + "/" + child.getName(), backupDir, skipNames);
            }
            return;
        }
        String name = file.getName();
        if (name.equals("session.lock") || name.endsWith(".jar")) {
            return;
        }
        for (String skip : skipNames) {
            if (name.equals(skip)) {
                return;
            }
        }
        addFile(zip, file, path);
    }

    private void addFile(ZipOutputStream zip, File file, String path) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            zip.putNextEntry(new ZipEntry(path));
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
            }
            zip.closeEntry();
        } catch (IOException e) {
            // A file that is in use or vanished shouldn't ruin the whole backup.
            plugin.getLogger().warning("Backup skipped " + path + ": " + e.getMessage());
        }
    }

    private void broadcastStaff(String key, Object... placeholders) {
        String message = plugin.messages().prefix() + plugin.messages().get(key, placeholders);
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("servercore.backup")) {
                player.sendMessage(message);
            }
        }
    }
}
