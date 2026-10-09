package com.killercats.servercore.economy.lottery;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Timed jackpot lottery. Ticket money forms the pot; the server keeps a configurable house cut
 * (a money sink) and unclaimed pots can roll over into the next draw.
 */
public final class LotteryManager {

    private final ServerCore plugin;
    private final File file;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Integer> tickets = new LinkedHashMap<UUID, Integer>();
    private final Map<UUID, String> names = new HashMap<UUID, String>();
    private double pot;
    private double rollover;
    private long nextDraw;
    private BukkitTask task;

    public LotteryManager(ServerCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lottery.yml");
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private ConfigurationSection config() {
        return plugin.getConfig().getConfigurationSection("lottery");
    }

    private void load() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        pot = data.getDouble("pot", 0);
        rollover = data.getDouble("rollover", 0);
        nextDraw = data.getLong("next-draw", 0);
        ConfigurationSection section = data.getConfigurationSection("tickets");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    tickets.put(uuid, section.getInt(key + ".count"));
                    names.put(uuid, section.getString(key + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        if (nextDraw <= 0) {
            scheduleNext();
        }
    }

    public void save() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("pot", pot);
        data.set("rollover", rollover);
        data.set("next-draw", nextDraw);
        for (Map.Entry<UUID, Integer> entry : tickets.entrySet()) {
            data.set("tickets." + entry.getKey() + ".count", entry.getValue());
            data.set("tickets." + entry.getKey() + ".name", names.get(entry.getKey()));
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save lottery.yml: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        save();
    }

    private void scheduleNext() {
        nextDraw = System.currentTimeMillis() + Math.max(1, config().getLong("draw-interval-minutes", 60)) * 60_000L;
    }

    private void tick() {
        if (!config().getBoolean("enabled", true)) {
            return;
        }
        long left = nextDraw - System.currentTimeMillis();
        long seconds = (left + 999) / 1000;
        if (left <= 0) {
            draw();
        } else if (seconds == 300 || seconds == 60) {
            broadcast("lottery.reminder", "time", TimeUtil.formatDuration(seconds * 1000), "pot", Money.format(jackpot()));
        }
    }

    public double ticketPrice() {
        return config().getDouble("ticket-price", 100);
    }

    public double jackpot() {
        return Money.round(pot * (1 - config().getDouble("house-cut-percent", 10) / 100D) + rollover);
    }

    public long timeLeft() {
        return Math.max(0, nextDraw - System.currentTimeMillis());
    }

    public int tickets(UUID uuid) {
        Integer count = tickets.get(uuid);
        return count == null ? 0 : count;
    }

    public int totalTickets() {
        int total = 0;
        for (int count : tickets.values()) {
            total += count;
        }
        return total;
    }

    public void buy(Player player, int amount) {
        if (!config().getBoolean("enabled", true)) {
            plugin.messages().send(player, "lottery.disabled");
            return;
        }
        int max = config().getInt("max-tickets-per-player", 50);
        int owned = tickets(player.getUniqueId());
        if (owned + amount > max) {
            plugin.messages().send(player, "lottery.max-tickets", "max", max);
            return;
        }
        double cost = Money.round(ticketPrice() * amount);
        if (!plugin.economy().withdraw(player, cost)) {
            plugin.messages().send(player, "general.insufficient-funds", "amount", Money.format(cost));
            return;
        }
        tickets.put(player.getUniqueId(), owned + amount);
        names.put(player.getUniqueId(), player.getName());
        pot = Money.round(pot + cost);
        save();
        plugin.transactions().log(TransactionType.LOTTERY_TICKET, player.getUniqueId(), null, -cost, amount + " ticket(s)");
        plugin.messages().send(player, "lottery.bought", "amount", amount, "cost", Money.format(cost), "total", owned + amount,
                "pot", Money.format(jackpot()));
    }

    public void draw() {
        int minPlayers = config().getInt("min-players", 2);
        if (tickets.size() < minPlayers) {
            if (!tickets.isEmpty()) {
                if (config().getBoolean("refund-if-not-enough-players", true)) {
                    for (Map.Entry<UUID, Integer> entry : tickets.entrySet()) {
                        double refund = Money.round(entry.getValue() * ticketPrice());
                        plugin.economy().deposit(Bukkit.getOfflinePlayer(entry.getKey()), refund);
                        plugin.transactions().log(TransactionType.LOTTERY_REFUND, entry.getKey(), null, refund, "Not enough players");
                    }
                    pot = 0;
                    tickets.clear();
                    names.clear();
                }
                broadcast("lottery.not-enough", "min", minPlayers);
            }
            scheduleNext();
            save();
            return;
        }
        int total = totalTickets();
        int roll = random.nextInt(total);
        UUID winner = null;
        for (Map.Entry<UUID, Integer> entry : tickets.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                winner = entry.getKey();
                break;
            }
        }
        double prize = jackpot();
        String winnerName = names.get(winner);
        int winnerTickets = tickets(winner);
        plugin.economy().deposit(Bukkit.getOfflinePlayer(winner), prize);
        plugin.transactions().log(TransactionType.LOTTERY_WIN, winner, null, prize, winnerTickets + "/" + total + " tickets");
        broadcast("lottery.winner", "player", winnerName, "amount", Money.format(prize), "tickets", winnerTickets, "total", total);
        pot = 0;
        rollover = 0;
        tickets.clear();
        names.clear();
        scheduleNext();
        save();
    }

    private void broadcast(String key, Object... placeholders) {
        String message = plugin.messages().prefix() + plugin.messages().get(key, placeholders);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    public void addToRollover(double amount) {
        rollover = Money.round(rollover + amount);
        save();
    }
}
