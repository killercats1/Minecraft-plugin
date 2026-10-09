package com.killercats.servercore.economy.daily;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import org.bukkit.command.CommandSender;

public final class DailyCommand extends BaseCommand {

    public DailyCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.daily");
        plugin.daily().claim(requirePlayer(sender));
    }
}
