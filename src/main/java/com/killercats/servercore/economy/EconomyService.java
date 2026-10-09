package com.killercats.servercore.economy;

import com.killercats.servercore.util.Money;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/**
 * Thin wrapper around the Vault economy provider (EssentialsX in the default setup) so wallet
 * balances stay in one place: /bal, /baltop and /eco from Essentials always agree with this plugin.
 * Must be used from the main thread.
 */
public final class EconomyService {

    private final Economy economy;

    public EconomyService(Economy economy) {
        this.economy = economy;
    }

    public String providerName() {
        return economy.getName();
    }

    public double balance(OfflinePlayer player) {
        return Money.round(economy.getBalance(player));
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy.has(player, Money.round(amount));
    }

    public boolean hasAccount(OfflinePlayer player) {
        return economy.hasAccount(player);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        double value = Money.round(amount);
        if (value < 0) {
            return false;
        }
        if (value == 0) {
            return true;
        }
        if (!economy.has(player, value)) {
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, value);
        return response != null && response.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        double value = Money.round(amount);
        if (value < 0) {
            return false;
        }
        if (value == 0) {
            return true;
        }
        if (!economy.hasAccount(player)) {
            economy.createPlayerAccount(player);
        }
        EconomyResponse response = economy.depositPlayer(player, value);
        return response != null && response.transactionSuccess();
    }
}
