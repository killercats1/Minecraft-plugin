package com.killercats.servercore.hook;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultHook {

    private Economy economy;
    private Permission permission;

    public boolean isVaultPresent() {
        return Bukkit.getPluginManager().getPlugin("Vault") != null;
    }

    public boolean setupEconomy() {
        if (!isVaultPresent()) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = rsp == null ? null : rsp.getProvider();
        return economy != null;
    }

    public boolean setupPermissions() {
        if (!isVaultPresent()) {
            return false;
        }
        RegisteredServiceProvider<Permission> rsp = Bukkit.getServicesManager().getRegistration(Permission.class);
        permission = rsp == null ? null : rsp.getProvider();
        return permission != null;
    }

    public Economy economy() {
        return economy;
    }

    public boolean hasGroups() {
        try {
            return permission != null && permission.hasGroupSupport();
        } catch (Throwable t) {
            return false;
        }
    }

    public String primaryGroup(Player player) {
        if (!hasGroups()) {
            return null;
        }
        try {
            return permission.getPrimaryGroup(player);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean inGroup(Player player, String group) {
        if (!hasGroups()) {
            return false;
        }
        try {
            if (permission.playerInGroup(player, group)) {
                return true;
            }
            String[] groups = permission.getPlayerGroups(player);
            if (groups != null) {
                for (String g : groups) {
                    if (g.equalsIgnoreCase(group)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // permission plugin not ready for this player yet
        }
        return false;
    }
}
