package com.killercats.servercore.command;

import com.killercats.servercore.ServerCore;
import org.bukkit.command.CommandSender;

/** Used for economy commands when no Vault economy provider (EssentialsX) is available. */
public final class UnavailableCommand extends BaseCommand {

    public UnavailableCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        msg(sender, "general.economy-unavailable");
    }
}
