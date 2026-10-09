package com.killercats.servercore.hook;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.economy.TransactionType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Listens to EssentialsX's UserBalanceUpdateEvent (without a compile-time dependency) so balance
 * changes made by Essentials commands such as /eco, /pay or /sell end up in the transaction history.
 * Changes caused through the Vault API are skipped because this plugin logs its own operations.
 */
public final class EssentialsBalanceListener implements Listener {

    private static final String EVENT_CLASS = "net.ess3.api.events.UserBalanceUpdateEvent";

    private final ServerCore plugin;
    private final Set<String> causes = new HashSet<String>();

    public EssentialsBalanceListener(ServerCore plugin) {
        this.plugin = plugin;
        List<String> configured = plugin.getConfig().getStringList("economy.transaction-log.essentials-causes");
        for (String cause : configured) {
            causes.add(cause.toUpperCase(Locale.ROOT));
        }
    }

    @SuppressWarnings("unchecked")
    public boolean register() {
        final Class<? extends Event> eventClass;
        final Method getPlayer;
        final Method getOld;
        final Method getNew;
        final Method getCause;
        try {
            eventClass = (Class<? extends Event>) Class.forName(EVENT_CLASS);
            getPlayer = eventClass.getMethod("getPlayer");
            getOld = eventClass.getMethod("getOldBalance");
            getNew = eventClass.getMethod("getNewBalance");
            getCause = eventClass.getMethod("getCause");
        } catch (Exception e) {
            return false;
        }
        Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR, (listener, event) -> {
            if (!eventClass.isInstance(event)) {
                return;
            }
            try {
                String cause = String.valueOf(getCause.invoke(event)).toUpperCase(Locale.ROOT);
                if (!causes.contains(cause)) {
                    return;
                }
                Object player = getPlayer.invoke(event);
                if (!(player instanceof OfflinePlayer)) {
                    return;
                }
                BigDecimal oldBalance = (BigDecimal) getOld.invoke(event);
                BigDecimal newBalance = (BigDecimal) getNew.invoke(event);
                double change = newBalance.subtract(oldBalance).doubleValue();
                if (change == 0) {
                    return;
                }
                plugin.transactions().log(TransactionType.ESSENTIALS, ((OfflinePlayer) player).getUniqueId(), null, change,
                        "Essentials " + cause.toLowerCase(Locale.ROOT).replace('_', ' '));
            } catch (Exception ignored) {
                // Essentials API changed; logging is best effort
            }
        }, plugin, true);
        return true;
    }
}
