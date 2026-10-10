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
                        "next", next < 0 ? plugin.messages().get("lagguard.sweep-off") : TimeUtil.formatDuration(next),
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
                if (!guard.sweepAvailable()) {
                    throw new CommandFail("lagguard.sweep-disabled");
                }
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
                Location target = safeSpot(world, cx, cz);
                if (target == null) {
                    throw new CommandFail("lagguard.no-safe-spot", "world", world.getName(), "x", cx, "z", cz);
                }
                player.teleport(target);
                msg(sender, "lagguard.teleported", "world", world.getName(), "x", cx, "z", cz);
                return;
            }
            default:
                plugin.messages().sendRaw(sender, "lagguard.help", "label", label);
        }
    }

    /**
     * A standing spot in the chunk: solid, non-lava floor with two free blocks above. Scans columns from
     * the top down (below the bedrock roof in the Nether), because 1.8's height map is unreliable there.
     */
    @SuppressWarnings("deprecation")
    static Location safeSpot(World world, int chunkX, int chunkZ) {
        boolean nether = world.getEnvironment() == World.Environment.NETHER;
        int top = nether ? 125 : world.getMaxHeight() - 2;
        int[][] offsets = {{8, 8}, {4, 4}, {12, 12}, {4, 12}, {12, 4}, {0, 0}, {15, 15}, {0, 15}, {15, 0}};
        for (int[] offset : offsets) {
            int x = chunkX * 16 + offset[0];
            int z = chunkZ * 16 + offset[1];
            for (int y = top; y >= 1; y--) {
                org.bukkit.block.Block floor = world.getBlockAt(x, y - 1, z);
                org.bukkit.block.Block feet = world.getBlockAt(x, y, z);
                org.bukkit.block.Block head = world.getBlockAt(x, y + 1, z);
                if (floor.getType().isSolid() && !isHazard(floor.getType()) && !feet.getType().isSolid() && !isHazard(feet.getType())
                        && !head.getType().isSolid() && !isHazard(head.getType())) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        // No standing spot: go to a mob in that chunk that stands on the ground.
        for (org.bukkit.entity.Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (entity instanceof org.bukkit.entity.LivingEntity && !(entity instanceof Player) && entity.isOnGround()) {
                return entity.getLocation();
            }
        }
        return null;
    }

    private static boolean isHazard(org.bukkit.Material type) {
        switch (type) {
            case LAVA:
            case STATIONARY_LAVA:
            case FIRE:
            case CACTUS:
            case WEB:
                return true;
            default:
                return false;
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
