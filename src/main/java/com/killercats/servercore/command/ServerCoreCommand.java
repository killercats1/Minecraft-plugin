package com.killercats.servercore.command;

import com.killercats.servercore.ServerCore;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class ServerCoreCommand extends BaseCommand {

    public ServerCoreCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            requirePermission(sender, "servercore.admin");
            plugin.reload();
            msg(sender, "general.reloaded");
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("version")) {
            msg(sender, "general.version", "version", plugin.getDescription().getVersion(),
                    "economy", plugin.economyReady() ? plugin.economy().providerName() : "none",
                    "essentials", plugin.essentials().version());
            return;
        }
        plugin.messages().sendRaw(sender, "general.help");
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? list("help", "reload", "version") : list();
    }

    /** /scoreboard - toggles the sidebar. */
    public static final class Scoreboard extends BaseCommand {

        public Scoreboard(ServerCore plugin) {
            super(plugin);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            requirePermission(sender, "servercore.scoreboard");
            if (!plugin.scoreboards().enabled()) {
                throw new CommandFail("scoreboard.disabled");
            }
            plugin.scoreboards().toggle(requirePlayer(sender));
        }
    }
}
