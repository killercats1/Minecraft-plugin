package com.killercats.servercore.vote;

import com.killercats.servercore.ServerCore;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Listens for Votifier / NuVotifier votes without a compile-time dependency: both fire
 * com.vexsoftware.votifier.model.VotifierEvent, so the event is looked up and read by reflection.
 */
public final class VotifierHook implements Listener {

    private static final String EVENT_CLASS = "com.vexsoftware.votifier.model.VotifierEvent";

    private final ServerCore plugin;
    private final VoteManager votes;
    private boolean hooked;

    public VotifierHook(ServerCore plugin, VoteManager votes) {
        this.plugin = plugin;
        this.votes = votes;
    }

    public boolean isHooked() {
        return hooked;
    }

    public boolean setup() {
        Plugin votifier = Bukkit.getPluginManager().getPlugin("Votifier");
        if (votifier == null) {
            votifier = Bukkit.getPluginManager().getPlugin("NuVotifier");
        }
        if (votifier == null || !votifier.isEnabled()) {
            return false;
        }
        Class<? extends Event> eventClass;
        Method getVote;
        try {
            eventClass = Class.forName(EVENT_CLASS, true, votifier.getClass().getClassLoader()).asSubclass(Event.class);
            getVote = eventClass.getMethod("getVote");
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().warning("Found " + votifier.getName() + " but could not read its vote event; vote rewards are off.");
            return false;
        }
        Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.NORMAL, (listener, event) -> {
            if (!eventClass.isInstance(event)) {
                return;
            }
            try {
                Object vote = getVote.invoke(event);
                String username = (String) vote.getClass().getMethod("getUsername").invoke(vote);
                String service = (String) vote.getClass().getMethod("getServiceName").invoke(vote);
                // Old Votifier fires the event off the main thread.
                plugin.sync(() -> votes.handleVote(username, service));
            } catch (ReflectiveOperationException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not read a Votifier vote", e);
            }
        }, plugin, true);
        hooked = true;
        return true;
    }
}
