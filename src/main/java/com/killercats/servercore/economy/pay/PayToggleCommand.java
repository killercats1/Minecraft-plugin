package com.killercats.servercore.economy.pay;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.data.PlayerData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PayToggleCommand extends BaseCommand {

    public PayToggleCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = requirePlayer(sender);
        requirePermission(sender, "servercore.paytoggle");
        PlayerData data = plugin.players().get(player);
        data.setAcceptPay(!data.isAcceptPay());
        msg(player, data.isAcceptPay() ? "pay.toggle-on" : "pay.toggle-off");
    }
}
