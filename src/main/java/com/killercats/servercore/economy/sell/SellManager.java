package com.killercats.servercore.economy.sell;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.economy.TransactionType;
import com.killercats.servercore.economy.notes.BanknoteManager;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Perms;
import com.killercats.servercore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sell-to-server shop with dynamic pricing: every unit sold lowers that item's price (supply and
 * demand), and prices slowly recover over time. This stops mass-farmed items from printing
 * unlimited money while keeping rare items valuable.
 */
public final class SellManager {

    public static final class Result {
        public double total;
        public int units;
        public final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        public final List<ItemStack> unsold = new ArrayList<ItemStack>();
    }

    private final ServerCore plugin;
    private final File pricesFile;
    private final File dataFile;
    private final Map<String, Double> prices = new HashMap<String, Double>();
    private final Map<String, Double> sold = new HashMap<String, Double>();

    private boolean dynamic;
    private double halfLife;
    private double minMultiplier;
    private double recoveryPercent;
    private boolean allowCustomItems;
    private BukkitTask recoveryTask;

    public SellManager(ServerCore plugin) {
        this.plugin = plugin;
        this.pricesFile = new File(plugin.getDataFolder(), "prices.yml");
        this.dataFile = new File(plugin.getDataFolder(), "sell-data.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = data.getConfigurationSection("sold");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                sold.put(key, section.getDouble(key));
            }
        }
        reload();
    }

    public void reload() {
        if (!pricesFile.exists()) {
            plugin.saveResource("prices.yml", false);
        }
        prices.clear();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(pricesFile);
        ConfigurationSection items = config.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                String normalized = normalize(key);
                if (normalized == null) {
                    plugin.getLogger().warning("prices.yml: unknown material '" + key + "'");
                    continue;
                }
                prices.put(normalized, items.getDouble(key));
            }
        }
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("sell");
        dynamic = c != null && c.getBoolean("dynamic-pricing.enabled", true);
        halfLife = c == null ? 2000 : Math.max(1, c.getDouble("dynamic-pricing.units-until-half-price", 2000));
        minMultiplier = c == null ? 0.25 : c.getDouble("dynamic-pricing.min-multiplier", 0.25);
        recoveryPercent = c == null ? 2 : c.getDouble("dynamic-pricing.recovery-percent-per-minute", 2);
        allowCustomItems = c != null && c.getBoolean("allow-custom-items", false);
        if (recoveryTask != null) {
            recoveryTask.cancel();
        }
        recoveryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::recover, 1200L, 1200L);
    }

    private static String normalize(String key) {
        String[] parts = key.split(":");
        Material material = Material.matchMaterial(parts[0]);
        if (material == null) {
            return null;
        }
        return parts.length > 1 ? material.name() + ":" + parts[1] : material.name();
    }

    public void shutdown() {
        if (recoveryTask != null) {
            recoveryTask.cancel();
        }
        save();
    }

    public void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<String, Double> entry : sold.entrySet()) {
            data.set("sold." + entry.getKey(), Math.round(entry.getValue() * 100) / 100D);
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save sell-data.yml: " + e.getMessage());
        }
    }

    private void recover() {
        if (!dynamic || sold.isEmpty()) {
            return;
        }
        double keep = 1 - recoveryPercent / 100D;
        List<String> remove = new ArrayList<String>();
        for (Map.Entry<String, Double> entry : sold.entrySet()) {
            double value = entry.getValue() * keep;
            if (value < 1) {
                remove.add(entry.getKey());
            } else {
                entry.setValue(value);
            }
        }
        for (String key : remove) {
            sold.remove(key);
        }
    }

    public void resetDynamic() {
        sold.clear();
        save();
    }

    /** Price key for an item, or null if the item can't be sold. */
    public String key(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        if (BanknoteManager.readId(item) != null) {
            return null;
        }
        if (!allowCustomItems && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() || meta.hasLore()) {
                return null;
            }
        }
        String exact = item.getType().name() + ":" + item.getDurability();
        if (prices.containsKey(exact)) {
            return exact;
        }
        return prices.containsKey(item.getType().name()) ? item.getType().name() : null;
    }

    private double durabilityFactor(ItemStack item) {
        short max = item.getType().getMaxDurability();
        if (max <= 0) {
            return 1;
        }
        return Math.max(0, (max - item.getDurability()) / (double) max);
    }

    public double factor(String key, double extraSold) {
        if (!dynamic) {
            return 1;
        }
        Double already = sold.get(key);
        double units = (already == null ? 0 : already) + extraSold;
        return Math.max(minMultiplier, Math.pow(0.5, units / halfLife));
    }

    public double multiplier(Player player) {
        double best = 1;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("sell.multipliers");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String permission = section.getString(key + ".permission", "servercore.sell.multiplier." + key);
                if (Perms.explicit(player, permission)) {
                    best = Math.max(best, section.getDouble(key + ".multiplier", 1));
                }
            }
        }
        return best;
    }

    /** Current price of one unit of this item (before the player's multiplier), or -1 if unsellable. */
    public double unitPrice(ItemStack item) {
        String key = key(item);
        if (key == null) {
            return -1;
        }
        return prices.get(key) * durabilityFactor(item) * factor(key, 0);
    }

    public double basePrice(String key) {
        Double price = prices.get(key);
        return price == null ? -1 : price;
    }

    /** Calculates (and if not simulated, applies) the sale of the given stacks. */
    public Result calculate(Player player, List<ItemStack> items, boolean simulate) {
        Result result = new Result();
        Map<String, Double> extra = new HashMap<String, Double>();
        double multiplier = multiplier(player);
        for (ItemStack item : items) {
            String key = key(item);
            if (key == null) {
                if (item != null && item.getType() != Material.AIR) {
                    result.unsold.add(item);
                }
                continue;
            }
            double base = prices.get(key) * durabilityFactor(item);
            double extraSold = extra.containsKey(key) ? extra.get(key) : 0;
            for (int i = 0; i < item.getAmount(); i++) {
                result.total += base * factor(key, extraSold) * multiplier;
                extraSold++;
            }
            extra.put(key, extraSold);
            result.units += item.getAmount();
            Integer count = result.counts.get(key);
            result.counts.put(key, (count == null ? 0 : count) + item.getAmount());
        }
        result.total = Money.round(result.total);
        if (!simulate && dynamic) {
            for (Map.Entry<String, Double> entry : extra.entrySet()) {
                Double before = sold.get(entry.getKey());
                sold.put(entry.getKey(), (before == null ? 0 : before) + entry.getValue());
            }
        }
        return result;
    }

    /**
     * Sells the stacks and pays the player. The caller must already have removed the sold stacks
     * from wherever they were; unsold stacks are returned in the result.
     */
    public Result sell(Player player, List<ItemStack> items) {
        Result result = calculate(player, items, false);
        if (result.units == 0) {
            return result;
        }
        if (!plugin.economy().deposit(player, result.total)) {
            plugin.getLogger().severe("Could not pay " + player.getName() + " " + result.total + " for sold items");
        }
        plugin.transactions().log(TransactionType.SELL, player.getUniqueId(), null, result.total, summary(result));
        return result;
    }

    public String summary(Result result) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : result.counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getValue()).append("x ").append(Text.capitalize(entry.getKey().split(":")[0]));
        }
        return sb.toString();
    }

    public String displayName(String key) {
        return Text.capitalize(key.split(":")[0].toLowerCase(Locale.ROOT));
    }
}
