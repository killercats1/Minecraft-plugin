package com.killercats.servercore.crates;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.CommandFail;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * /crate - menu with your keys.
 * /crate open|preview <crate>
 * /crate keys [player]
 * /crate givekey <player|all> <crate> [amount] (console: votes, store)
 * /crate takekey <player> <crate> [amount]
 * /crate set <crate> | remove (look at a block)
 */
public final class CrateCommand extends BaseCommand {

    public CrateCommand(ServerCore plugin) {
        super(plugin);
    }

    private CrateManager crates() {
        if (!plugin.crates().enabled()) {
            throw new CommandFail("crates.disabled");
        }
        return plugin.crates();
    }

    private Crate requireCrate(String id) {
        Crate crate = plugin.crates().get(id);
        if (crate == null) {
            throw new CommandFail("crates.unknown", "crate", id, "crates", String.join(", ", plugin.crates().crates().keySet()));
        }
        return crate;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.crate");
        CrateManager crates = crates();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "": {
                Player player = requirePlayer(sender);
                crates.allKeys(player.getUniqueId()).whenComplete((keys, error) -> plugin.sync(() -> {
                    if (error != null) {
                        msg(player, "general.error");
                    } else if (player.isOnline()) {
                        new CratePreviewMenu.ListMenu(plugin, keys).open(player);
                    }
                }));
                return;
            }
            case "open": {
                Player player = requirePlayer(sender);
                if (args.length < 2) {
                    usage("/" + label + " open <crate>");
                }
                crates.open(player, requireCrate(args[1]));
                return;
            }
            case "preview": {
                Player player = requirePlayer(sender);
                if (args.length < 2) {
                    usage("/" + label + " preview <crate>");
                }
                new CratePreviewMenu(plugin, requireCrate(args[1])).open(player);
                return;
            }
            case "keys": {
                String name;
                if (args.length > 1) {
                    requirePermission(sender, "servercore.crate.admin");
                    name = args[1];
                } else {
                    name = requirePlayer(sender).getName();
                }
                crates.resolveUuid(name).thenCompose(uuid -> uuid == null
                        ? java.util.concurrent.CompletableFuture.completedFuture((Map<String, Integer>) null)
                        : crates.allKeys(uuid)).whenComplete((keys, error) -> plugin.sync(() -> {
                    if (error != null) {
                        msg(sender, "general.error");
                        return;
                    }
                    if (keys == null) {
                        msg(sender, "general.player-not-found", "player", name);
                        return;
                    }
                    msg(sender, "crates.keys-header", "player", name);
                    for (Crate crate : crates.crates().values()) {
                        msg(sender, "crates.keys-line", "crate", crate.name, "keys", keys.containsKey(crate.id) ? keys.get(crate.id) : 0);
                    }
                }));
                return;
            }
            case "givekey":
            case "takekey": {
                requirePermission(sender, "servercore.crate.admin");
                if (args.length < 3) {
                    usage("/" + label + " " + sub + " <player" + (sub.equals("givekey") ? "|all" : "") + "> <crate> [amount]");
                }
                Crate crate = requireCrate(args[2]);
                int amount = args.length > 3 ? requireInt(args[3], 1, 10000) : 1;
                int signed = sub.equals("givekey") ? amount : -amount;
                if (sub.equals("givekey") && args[1].equalsIgnoreCase("all")) {
                    int count = 0;
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        crates.addKeys(online.getUniqueId(), crate.id, amount);
                        msg(online, "crates.key-received", "amount", amount, "crate", crate.name);
                        count++;
                    }
                    msg(sender, "crates.gave-all", "amount", amount, "crate", crate.name, "count", count);
                    return;
                }
                String name = args[1];
                crates.resolveUuid(name).whenComplete((uuid, error) -> plugin.sync(() -> {
                    if (error != null || uuid == null) {
                        msg(sender, error != null ? "general.error" : "general.player-not-found", "player", name);
                        return;
                    }
                    crates.addKeys(uuid, crate.id, signed);
                    msg(sender, signed > 0 ? "crates.gave" : "crates.took", "amount", amount, "crate", crate.name, "player", name);
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && signed > 0) {
                        msg(online, "crates.key-received", "amount", amount, "crate", crate.name);
                    }
                }));
                return;
            }
            case "set": {
                requirePermission(sender, "servercore.crate.admin");
                Player player = requirePlayer(sender);
                if (args.length < 2) {
                    usage("/" + label + " set <crate>");
                }
                Crate crate = requireCrate(args[1]);
                Block block = target(player);
                crates.setBlock(block, crate);
                msg(player, "crates.block-set", "crate", crate.name);
                return;
            }
            case "remove": {
                requirePermission(sender, "servercore.crate.admin");
                Player player = requirePlayer(sender);
                msg(player, crates.removeBlock(target(player)) ? "crates.block-removed" : "crates.not-a-crate");
                return;
            }
            default:
                plugin.messages().sendRaw(sender, sender.hasPermission("servercore.crate.admin") ? "crates.help-admin" : "crates.help");
        }
    }

    @SuppressWarnings("deprecation")
    private static Block target(Player player) {
        Block block = player.getTargetBlock((HashSet<Byte>) null, 6);
        if (block == null || block.getType() == Material.AIR) {
            throw new CommandFail("crates.look-at-block");
        }
        return block;
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission("servercore.crate.admin");
        if (args.length == 1) {
            return admin ? list("open", "preview", "keys", "givekey", "takekey", "set", "remove") : list("open", "preview", "keys");
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        List<String> ids = new ArrayList<String>(plugin.crates().crates().keySet());
        if (args.length == 2) {
            if (sub.equals("open") || sub.equals("preview") || sub.equals("set")) {
                return ids;
            }
            if (sub.equals("givekey") || sub.equals("takekey") || sub.equals("keys")) {
                return null;
            }
        }
        if (args.length == 3 && (sub.equals("givekey") || sub.equals("takekey"))) {
            return ids;
        }
        return list();
    }
}
