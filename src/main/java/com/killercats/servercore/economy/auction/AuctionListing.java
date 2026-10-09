package com.killercats.servercore.economy.auction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class AuctionListing {

    public enum State {
        ACTIVE, SOLD, EXPIRED, CANCELLED
    }

    private final long id;
    private final UUID seller;
    private final String sellerName;
    private final ItemStack item;
    private final double price;
    private final long created;
    private final long expires;
    private State state;
    private UUID buyer;
    private boolean collected;

    public AuctionListing(long id, UUID seller, String sellerName, ItemStack item, double price, long created, long expires,
                          State state, UUID buyer, boolean collected) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.price = price;
        this.created = created;
        this.expires = expires;
        this.state = state;
        this.buyer = buyer;
        this.collected = collected;
    }

    public long id() {
        return id;
    }

    public UUID seller() {
        return seller;
    }

    public String sellerName() {
        return sellerName;
    }

    /** A copy of the listed item. */
    public ItemStack item() {
        return item.clone();
    }

    public double price() {
        return price;
    }

    public long created() {
        return created;
    }

    public long expires() {
        return expires;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public UUID buyer() {
        return buyer;
    }

    public void setBuyer(UUID buyer) {
        this.buyer = buyer;
    }

    public boolean collected() {
        return collected;
    }

    public void setCollected(boolean collected) {
        this.collected = collected;
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }
}
