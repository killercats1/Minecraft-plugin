package com.killercats.servercore.data;

import java.util.UUID;

/** Per-player persistent state. Only mutated on the main server thread. */
public final class PlayerData {

    private final UUID uuid;
    private String name;
    private double bankBalance;
    private int bankTier;
    private long lastDaily;
    private int dailyStreak;
    private boolean acceptPay = true;
    private boolean scoreboard = true;
    private int paydayMinutes;
    private long lastSeen;
    private volatile boolean dirty;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        dirty = true;
    }

    public double getBankBalance() {
        return bankBalance;
    }

    public void setBankBalance(double bankBalance) {
        this.bankBalance = Math.max(0, com.killercats.servercore.util.Money.round(bankBalance));
        dirty = true;
    }

    public int getBankTier() {
        return bankTier;
    }

    public void setBankTier(int bankTier) {
        this.bankTier = bankTier;
        dirty = true;
    }

    public long getLastDaily() {
        return lastDaily;
    }

    public void setLastDaily(long lastDaily) {
        this.lastDaily = lastDaily;
        dirty = true;
    }

    public int getDailyStreak() {
        return dailyStreak;
    }

    public void setDailyStreak(int dailyStreak) {
        this.dailyStreak = dailyStreak;
        dirty = true;
    }

    public boolean isAcceptPay() {
        return acceptPay;
    }

    public void setAcceptPay(boolean acceptPay) {
        this.acceptPay = acceptPay;
        dirty = true;
    }

    public boolean isScoreboard() {
        return scoreboard;
    }

    public void setScoreboard(boolean scoreboard) {
        this.scoreboard = scoreboard;
        dirty = true;
    }

    public int getPaydayMinutes() {
        return paydayMinutes;
    }

    public void setPaydayMinutes(int paydayMinutes) {
        this.paydayMinutes = paydayMinutes;
        dirty = true;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /** Values in the column order of {@link PlayerDataManager#COLUMNS}; taken on the main thread. */
    Object[] snapshot() {
        return new Object[]{uuid.toString(), name, bankBalance, bankTier, lastDaily, dailyStreak,
                acceptPay ? 1 : 0, scoreboard ? 1 : 0, paydayMinutes, lastSeen};
    }
}
