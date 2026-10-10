package com.killercats.servercore.heads;

/** Which game a player joined with, as far as the server can tell. */
public enum ClientType {
    EAGLER("Eaglercraft"),
    JAVA("Java"),
    UNKNOWN("?");

    private final String display;

    ClientType(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    static ClientType fromId(int id) {
        return id == 1 ? EAGLER : id == 2 ? JAVA : UNKNOWN;
    }

    int id() {
        return this == EAGLER ? 1 : this == JAVA ? 2 : 0;
    }
}
