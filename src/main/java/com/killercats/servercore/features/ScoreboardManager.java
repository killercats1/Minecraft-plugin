package com.killercats.servercore.features;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.data.PlayerData;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Flicker-free sidebar. Each line is a team whose prefix/suffix carry the text, which gives up to
 * 32 visible characters per line on 1.8 and allows updating lines without re-creating scores.
 */
public final class ScoreboardManager implements Listener {

    private static final int MAX_LINES = 15;

    private final ServerCore plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<UUID, Scoreboard>();
    private BukkitTask task;

    public ScoreboardManager(ServerCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("scoreboard.enabled", true);
    }

    public void reload() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            remove(player);
        }
        if (!enabled()) {
            return;
        }
        long interval = Math.max(10, plugin.getConfig().getLong("scoreboard.update-ticks", 40));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, interval, interval);
        for (Player player : Bukkit.getOnlinePlayers()) {
            create(player);
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            remove(player);
        }
    }

    public void create(Player player) {
        if (!enabled() || !plugin.players().get(player).isScoreboard()) {
            return;
        }
        if (plugin.getConfig().getStringList("scoreboard.disabled-worlds").contains(player.getWorld().getName())) {
            return;
        }
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("sc_sidebar", "dummy");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.setDisplayName(Text.trim(Text.color(plugin.getConfig().getString("scoreboard.title", "&6&lSERVER")), 32));
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        update(player);
    }

    public void remove(Player player) {
        if (boards.remove(player.getUniqueId()) != null && player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    public void toggle(Player player) {
        PlayerData data = plugin.players().get(player);
        data.setScoreboard(!data.isScoreboard());
        if (data.isScoreboard()) {
            create(player);
        } else {
            remove(player);
        }
        plugin.messages().send(player, data.isScoreboard() ? "scoreboard.toggled-on" : "scoreboard.toggled-off");
    }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (boards.containsKey(player.getUniqueId())) {
                update(player);
            }
        }
    }

    private String placeholders(Player player, String line) {
        PlayerData data = plugin.players().get(player);
        String out = line;
        if (out.contains("{balance}")) {
            out = out.replace("{balance}", plugin.economyReady() ? Money.shortFormat(plugin.economy().balance(player)) : "-");
        }
        if (out.contains("{bank}")) {
            out = out.replace("{bank}", Money.shortFormat(data.getBankBalance()));
        }
        if (out.contains("{bounty}")) {
            out = out.replace("{bounty}", plugin.economyReady() ? Money.shortFormat(plugin.bounties().amount(player.getUniqueId())) : "-");
        }
        if (out.contains("{rank}")) {
            String rank = plugin.vault().primaryGroup(player);
            out = out.replace("{rank}", rank == null ? "-" : Text.capitalize(rank));
        }
        if (out.contains("{lottery}")) {
            out = out.replace("{lottery}", plugin.economyReady() ? Money.shortFormat(plugin.lottery().jackpot()) : "-");
        }
        out = out.replace("{player}", player.getName())
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("{world}", player.getWorld().getName())
                .replace("{streak}", String.valueOf(data.getDailyStreak()))
                .replace("{maintenance}", plugin.maintenance().isEnabled() ? "&cON" : "&aOFF");
        return Text.color(plugin.placeholders().apply(player, out));
    }

    private void update(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            return;
        }
        Objective objective = board.getObjective("sc_sidebar");
        if (objective == null) {
            return;
        }
        List<String> lines = plugin.getConfig().getStringList("scoreboard.lines");
        int count = Math.min(MAX_LINES, lines.size());
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            Team team = board.getTeam("sc_line_" + i);
            if (i >= count) {
                if (team != null) {
                    board.resetScores(entry);
                    team.unregister();
                }
                continue;
            }
            if (team == null) {
                team = board.registerNewTeam("sc_line_" + i);
                team.addEntry(entry);
            }
            String text = placeholders(player, lines.get(i));
            String prefix = Text.trim(text, 16);
            String suffix = "";
            if (text.length() > prefix.length()) {
                suffix = Text.trim(ChatColor.getLastColors(prefix) + text.substring(prefix.length()), 16);
            }
            if (!prefix.equals(team.getPrefix())) {
                team.setPrefix(prefix);
            }
            if (!suffix.equals(team.getSuffix())) {
                team.setSuffix(suffix);
            }
            objective.getScore(entry).setScore(count - i);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                create(player);
            }
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
    }
}
