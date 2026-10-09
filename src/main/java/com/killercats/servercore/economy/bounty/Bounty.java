package com.killercats.servercore.economy.bounty;

import com.killercats.servercore.util.Money;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Bounty {

    private final UUID target;
    private String targetName;
    private final Map<UUID, Double> contributions = new LinkedHashMap<UUID, Double>();

    public Bounty(UUID target, String targetName) {
        this.target = target;
        this.targetName = targetName;
    }

    public UUID target() {
        return target;
    }

    public String targetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public Map<UUID, Double> contributions() {
        return contributions;
    }

    public void add(UUID contributor, double amount) {
        Double before = contributions.get(contributor);
        contributions.put(contributor, Money.round((before == null ? 0 : before) + amount));
    }

    public double total() {
        double total = 0;
        for (double value : contributions.values()) {
            total += value;
        }
        return Money.round(total);
    }

    public String serializeContributions() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Double> entry : contributions.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    public void deserializeContributions(String data) {
        contributions.clear();
        if (data == null || data.isEmpty()) {
            return;
        }
        for (String part : data.split(";")) {
            String[] kv = part.split("=");
            if (kv.length == 2) {
                try {
                    contributions.put(UUID.fromString(kv[0]), Double.parseDouble(kv[1]));
                } catch (IllegalArgumentException ignored) {
                    // skip corrupt entry
                }
            }
        }
    }
}
