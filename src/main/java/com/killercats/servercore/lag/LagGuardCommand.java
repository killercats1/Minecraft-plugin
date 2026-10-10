package com.killercats.servercore.lag;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.CommandFail;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /lagguard status|chunks|sweep|tp */
public final class LagGuardCommand extends BaseCommand {

    private final LagGuard guard;

    public LagGuardCommand(ServerCore plugin, LagGuard guard) {
        super(plugin);
        this.guard = guard;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.lagguard");
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status": {
                double[] tps = guard.meter().tps();
                Runtime runtime = Runtime.getRuntime();
                long used = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
                long max = runtime.maxMemory() / 1024 / 1024;
                long next = guard.nextSweepIn();
                plugin.messages().sendRaw(sender, "lagguard.status",
                        "tps1", TpsMeter.colored(tps[0]), "tps5", TpsMeter.colored(tps[1]), "tps15", TpsMeter.colored(tps[2]),
                        "used", used, "max", max,
                        "next", next < 0 ? plugin.messages().get("lagguard.off") : TimeUtil.formatDuration(next),
                        "last", guard.lastSweepTime() == 0 ? "-" : TimeUtil.formatDuration(System.currentTimeMillis() - guard.lastSweepTime()),
                        "removed", guard.lastSweepRemoved(), "blocked", guard.spawnsBlocked(),
                        "emergency", plugin.messages().get(guard.inEmergency() ? "lagguard.emergency-on" : "lagguard.emergency-off"));
                for (Map.Entry<String, int[]> entry : guard.entityCounts().entrySet()) {
                    int[] c = entry.getValue();
                    plugin.messages().sendRaw(sender, "lagguard.world-line", "world", entry.getKey(), "items", c[0], "monsters", c[1],
                            "animals", c[2], "other", c[3], "chunks", c[4]);
                }
                return;
            }
            case "chunks": {
                int n = args.length > 1 ? requireInt(args[1], 1, 20) : 5;
                plugin.messages().sendRaw(sender, "lagguard.chunks-header", "count", n);
                guard.sendChunks(sender, guard.busiestChunks(n));
                return;
            }
            case "sweep":
                if (args.length > 1 && args[1].equalsIgnoreCase("now")) {
                    msg(sender, "lagguard.swept-now", "count", guard.sweep(true));
                } else {
                    guard.sweepIn(10);
                    msg(sender, "lagguard.sweep-scheduled");
                }
                return;
            case "tp": {
                Player player = requirePlayer(sender);
                if (args.length < 4) {
                    usage("/" + label + " tp <world> <chunkX> <chunkZ>");
                }
                World world = Bukkit.getWorld(args[1]);
                if (world == null) {
                    throw new CommandFail("lagguard.unknown-world", "world", args[1]);
                }
                int cx = requireInt(args[2], -2_000_000, 2_000_000);
                int cz = requireInt(args[3], -2_000_000, 2_000_000);
                int x = cx * 16 + 8;
                int z = cz * 16 + 8;
                player.teleport(new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5));
                msg(sender, "lagguard.teleported", "world", world.getName(), "x", cx, "z", cz);
                return;
            }
            default:
                plugin.messages().sendRaw(sender, "lagguard.help", "label", label);
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return list("status", "chunks", "sweep", "tp");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sweep")) {
            return list("now");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
            List<String> worlds = new ArrayList<String>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            return worlds;
        }
        return list();
    }
}
