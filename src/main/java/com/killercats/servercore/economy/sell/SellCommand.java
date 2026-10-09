package com.killercats.servercore.economy.sell;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.CommandFail;
import com.killercats.servercore.util.Money;
import com.killercats.servercore.util.Sounds;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SellCommand extends BaseCommand {

    public SellCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player player = requirePlayer(sender);
        requirePermission(sender, "servercore.sell");
        String mode = args.length == 0 ? "gui" : args[0].toLowerCase(Locale.ROOT);
        SellManager sell = plugin.sell();
        ItemStack[] contents = player.getInventory().getContents();
        List<ItemStack> toSell = new ArrayList<ItemStack>();
        switch (mode) {
            case "gui":
                new SellMenu(plugin, player).open(player);
                return;
            case "hand": {
                ItemStack hand = player.getItemInHand();
                if (sell.key(hand) == null) {
                    throw new CommandFail("sell.not-sellable");
                }
                toSell.add(hand);
                player.setItemInHand(null);
                break;
            }
            case "handall":
            case "same": {
                ItemStack hand = player.getItemInHand();
                if (sell.key(hand) == null) {
                    throw new CommandFail("sell.not-sellable");
                }
                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] != null && contents[i].isSimilar(hand)) {
                        toSell.add(contents[i]);
                        player.getInventory().setItem(i, null);
                    }
                }
                break;
            }
            case "all":
            case "inventory":
                requirePermission(sender, "servercore.sell.all");
                for (int i = 0; i < contents.length; i++) {
                    if (sell.key(contents[i]) != null) {
                        toSell.add(contents[i]);
                        player.getInventory().setItem(i, null);
                    }
                }
                break;
            default:
                usage("/" + label + " [hand|handall|all]");
                return;
        }
        if (toSell.isEmpty()) {
            throw new CommandFail("sell.nothing");
        }
        SellManager.Result result = sell.sell(player, toSell);
        for (ItemStack rest : result.unsold) {
            player.getInventory().addItem(rest);
        }
        msg(player, "sell.sold", "amount", result.units, "money", Money.format(result.total));
        Sounds.play(player, plugin.getConfig().getString("sounds.money-received", "ORB_PICKUP"));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? list("hand", "handall", "all") : Collections.<String>emptyList();
    }

    /** /worth [hand|material] */
    public static final class Worth extends BaseCommand {

        public Worth(ServerCore plugin) {
            super(plugin);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            requirePermission(sender, "servercore.worth");
            ItemStack item;
            if (args.length == 0 || args[0].equalsIgnoreCase("hand")) {
                item = requirePlayer(sender).getItemInHand();
            } else {
                String[] parts = args[0].split(":");
                Material material = Material.matchMaterial(parts[0]);
                if (material == null) {
                    throw new CommandFail("sell.unknown-item", "item", args[0]);
                }
                short data = 0;
                if (parts.length > 1) {
                    try {
                        data = Short.parseShort(parts[1]);
                    } catch (NumberFormatException ignored) {
                        // keep 0
                    }
                }
                item = new ItemStack(material, 1, data);
            }
            SellManager sell = plugin.sell();
            String key = sell.key(item);
            if (key == null) {
                throw new CommandFail("sell.not-sellable");
            }
            double unit = sell.unitPrice(item);
            double multiplier = sender instanceof Player ? sell.multiplier((Player) sender) : 1;
            plugin.messages().sendRaw(sender, "sell.worth", "item", sell.displayName(key), "base", Money.format(sell.basePrice(key)),
                    "price", Money.format(unit * multiplier), "stack", Money.format(unit * multiplier * item.getMaxStackSize()),
                    "market", Money.percent(sell.factor(key, 0) * 100), "multiplier", multiplier);
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 ? list("hand") : Collections.<String>emptyList();
        }
    }
}
