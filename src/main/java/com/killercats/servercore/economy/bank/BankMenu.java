package com.killercats.servercore.economy.bank;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.data.PlayerData;
import com.killercats.servercore.gui.Menu;
import com.killercats.servercore.util.ItemBuilder;
import com.killercats.servercore.util.Money;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class BankMenu extends Menu {

    private final ServerCore plugin;
    private final Player player;

    public BankMenu(ServerCore plugin, Player player) {
        super(plugin.messages().get("bank.menu-title"), 3);
        this.plugin = plugin;
        this.player = player;
        render();
    }

    private void render() {
        clear();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, ItemBuilder.filler());
        }
        PlayerData data = plugin.players().get(player);
        BankManager bank = plugin.bank();
        BankTier tier = bank.tier(data);
        BankTier next = bank.nextTier(data);
        double wallet = plugin.economy().balance(player);

        set(4, new ItemBuilder(Material.GOLD_BLOCK).name("&6&lYour Bank Account").lore(
                "&7Balance: &a" + Money.format(data.getBankBalance()),
                "&7Wallet: &a" + Money.format(wallet),
                "&7Tier: " + tier.name(),
                "&7Capacity: &f" + Money.format(tier.maxBalance()),
                "&7Interest: &a" + Money.percent(tier.interestPercent()) + " &7every &f"
                        + plugin.getConfig().getLong("bank.interest.interval-minutes", 30) + "m").build(), null);

        double[] fractions = {0.25, 0.5, 1.0};
        int[] depositSlots = {10, 11, 12};
        int[] withdrawSlots = {14, 15, 16};
        for (int i = 0; i < fractions.length; i++) {
            final double fraction = fractions[i];
            String label = fraction == 1.0 ? "everything" : (int) (fraction * 100) + "%";
            set(depositSlots[i], new ItemBuilder(Material.EMERALD, i + 1, (short) 0).name("&aDeposit " + label)
                    .lore("&7Amount: &f" + Money.format(Money.round(wallet * fraction)), "", "&eClick to deposit").build(), (p, e) -> {
                double amount = Money.round(plugin.economy().balance(p) * fraction);
                if (amount > 0) {
                    plugin.bank().deposit(p, amount);
                }
                render();
            });
            set(withdrawSlots[i], new ItemBuilder(Material.GOLD_INGOT, i + 1, (short) 0).name("&cWithdraw " + label)
                    .lore("&7Amount: &f" + Money.format(Money.round(data.getBankBalance() * fraction)), "", "&eClick to withdraw").build(), (p, e) -> {
                double amount = Money.round(plugin.players().get(p).getBankBalance() * fraction);
                if (amount > 0) {
                    plugin.bank().withdraw(p, amount);
                }
                render();
            });
        }
        if (next != null) {
            set(22, new ItemBuilder(Material.NETHER_STAR).name("&b&lUpgrade to " + next.name()).lore(
                    "&7Cost: &c" + Money.format(next.cost()),
                    "&7Capacity: &f" + Money.format(next.maxBalance()),
                    "&7Interest: &a" + Money.percent(next.interestPercent()),
                    "", "&eClick to upgrade").build(), (p, e) -> {
                plugin.bank().upgrade(p);
                render();
            });
        } else {
            set(22, new ItemBuilder(Material.NETHER_STAR).name("&b&lMaximum tier reached").build(), null);
        }
        set(18, new ItemBuilder(Material.BOOK).name("&eTransaction history").lore("&7Use &f/transactions").build(), (p, e) -> {
            p.closeInventory();
            p.performCommand("transactions");
        });
        set(26, new ItemBuilder(Material.BARRIER).name("&cClose").build(), (p, e) -> p.closeInventory());
    }
}
