package com.killercats.servercore.command;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public abstract class BaseCommand implements CommandExecutor, TabCompleter {

    protected final ServerCore plugin;

    protected BaseCommand(ServerCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            execute(sender, label, args);
        } catch (CommandFail fail) {
            plugin.messages().send(sender, fail.key(), fail.placeholders());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error while executing /" + label, e);
            plugin.messages().send(sender, "general.error");
        }
        return true;
    }

    protected abstract void execute(CommandSender sender, String label, String[] args);

    @Override
    public final List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = complete(sender, args);
        if (options == null) {
            return null;
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(last)) {
                out.add(option);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Return null for the default (online player names). */
    protected List<String> complete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }

    protected static List<String> list(String... values) {
        return Arrays.asList(values);
    }

    protected Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            throw new CommandFail("general.players-only");
        }
        return (Player) sender;
    }

    protected void requirePermission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            throw new CommandFail("general.no-permission");
        }
    }

    protected Player requireOnline(String name) {
        Player player = Bukkit.getPlayer(name);
        if (player == null) {
            throw new CommandFail("general.player-not-found", "player", name);
        }
        return player;
    }

    protected double requireAmount(String input) {
        double amount = Money.parse(input);
        if (amount <= 0) {
            throw new CommandFail("general.invalid-amount", "input", input);
        }
        return amount;
    }

    protected int requireInt(String input, int min, int max) {
        try {
            int value = Integer.parseInt(input);
            if (value < min || value > max) {
                throw new CommandFail("general.invalid-number", "input", input, "min", min, "max", max);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new CommandFail("general.invalid-number", "input", input, "min", min, "max", max);
        }
    }

    protected void usage(String usage) {
        throw new CommandFail("general.usage", "usage", usage);
    }

    protected void msg(CommandSender sender, String key, Object... placeholders) {
        plugin.messages().send(sender, key, placeholders);
    }
}
