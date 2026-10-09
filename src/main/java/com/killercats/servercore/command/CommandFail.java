package com.killercats.servercore.command;

/** Thrown inside commands to abort with a message key from messages.yml. */
public final class CommandFail extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final Object[] placeholders;

    public CommandFail(String key, Object... placeholders) {
        super(key, null, false, false);
        this.key = key;
        this.placeholders = placeholders;
    }

    public String key() {
        return key;
    }

    public Object[] placeholders() {
        return placeholders;
    }
}
