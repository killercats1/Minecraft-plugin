package com.killercats.servercore.economy.bank;

public final class BankTier {

    private final int index;
    private final String name;
    private final double cost;
    private final double maxBalance;
    private final double interestPercent;
    private final String permission;

    public BankTier(int index, String name, double cost, double maxBalance, double interestPercent, String permission) {
        this.index = index;
        this.name = name;
        this.cost = cost;
        this.maxBalance = maxBalance;
        this.interestPercent = interestPercent;
        this.permission = permission;
    }

    public int index() {
        return index;
    }

    public String name() {
        return name;
    }

    public double cost() {
        return cost;
    }

    public double maxBalance() {
        return maxBalance;
    }

    public double interestPercent() {
        return interestPercent;
    }

    public String permission() {
        return permission;
    }
}
