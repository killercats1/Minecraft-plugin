package com.killercats.servercore.crates;

import com.killercats.servercore.util.ItemBuilder;
import com.killercats.servercore.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Logger;

/** A crate from config.yml: its name, look and weighted rewards. */
public final class Crate {

    public static final class Reward {
        public final String id;
        public final int weight;
        public final ItemStack display;
        public final boolean giveItem;
        public final double money;
        public final List<String> commands;
        public final boolean broadcast;

        Reward(String id, int weight, ItemStack display, boolean giveItem, double money, List<String> commands, boolean broadcast) {
            this.id = id;
            this.weight = weight;
            this.display = display;
            this.giveItem = giveItem;
            this.money = money;
            this.commands = commands;
            this.broadcast = broadcast;
        }

        public String displayName() {
            ItemMeta meta = display.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                return meta.getDisplayName();
            }
            return (display.getAmount() > 1 ? display.getAmount() + "x " : "") + Text.capitalize(display.getType().name());
        }
    }

    private static final Random RANDOM = new Random();

    public final String id;
    public final String name;
    public final Material icon;
    public final List<Reward> rewards;
    private final int totalWeight;

    private Crate(String id, String name, Material icon, List<Reward> rewards) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.rewards = Collections.unmodifiableList(rewards);
        int total = 0;
        for (Reward reward : rewards) {
            total += reward.weight;
        }
        this.totalWeight = total;
    }

    public Reward roll() {
        int pick = RANDOM.nextInt(totalWeight);
        for (Reward reward : rewards) {
            pick -= reward.weight;
            if (pick < 0) {
                return reward;
            }
        }
        return rewards.get(rewards.size() - 1);
    }

    public Reward randomForDisplay() {
        return rewards.get(RANDOM.nextInt(rewards.size()));
    }

    /** Chance of a reward in percent. */
    public double chance(Reward reward) {
        return reward.weight * 100.0 / totalWeight;
    }

    static Crate load(String id, ConfigurationSection section, Logger logger) {
        List<Reward> rewards = new ArrayList<Reward>();
        ConfigurationSection rewardSection = section.getConfigurationSection("rewards");
        if (rewardSection != null) {
            for (String rewardId : rewardSection.getKeys(false)) {
                ConfigurationSection r = rewardSection.getConfigurationSection(rewardId);
                if (r == null) {
                    continue;
                }
                int weight = r.getInt("weight", 1);
                if (weight <= 0) {
                    continue;
                }
                ItemStack item = item(r.getConfigurationSection("item"), id + "." + rewardId, logger);
                rewards.add(new Reward(rewardId, weight, item, r.getBoolean("give-item", false), r.getDouble("money", 0),
                        r.getStringList("commands"), r.getBoolean("broadcast", false)));
            }
        }
        if (rewards.isEmpty()) {
            logger.warning("Crate '" + id + "' has no rewards and was skipped.");
            return null;
        }
        Material icon = Material.matchMaterial(section.getString("icon", "CHEST"));
        return new Crate(id, section.getString("name", id), icon == null ? Material.CHEST : icon, rewards);
    }

    @SuppressWarnings("deprecation")
    private static ItemStack item(ConfigurationSection section, String where, Logger logger) {
        if (section == null) {
            return new ItemStack(Material.CHEST);
        }
        Material material = Material.matchMaterial(section.getString("material", "STONE"));
        if (material == null) {
            logger.warning("Crate reward " + where + " has an unknown material '" + section.getString("material") + "'.");
            material = Material.STONE;
        }
        ItemBuilder builder = new ItemBuilder(material, Math.max(1, Math.min(64, section.getInt("amount", 1))), (short) section.getInt("data", 0));
        if (section.isString("name")) {
            builder.name(section.getString("name"));
        }
        if (section.isList("lore")) {
            builder.lore(section.getStringList("lore"));
        }
        ItemStack item = builder.build();
        ConfigurationSection enchants = section.getConfigurationSection("enchants");
        if (enchants != null) {
            for (String key : enchants.getKeys(false)) {
                Enchantment enchantment = Enchantment.getByName(key.toUpperCase(Locale.ROOT));
                if (enchantment == null) {
                    logger.warning("Crate reward " + where + " has an unknown enchantment '" + key + "' (use 1.8 names like DAMAGE_ALL).");
                    continue;
                }
                item.addUnsafeEnchantment(enchantment, Math.max(1, enchants.getInt(key)));
            }
        }
        return item;
    }
}
