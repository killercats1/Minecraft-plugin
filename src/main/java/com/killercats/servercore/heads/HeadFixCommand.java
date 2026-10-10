package com.killercats.servercore.heads;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.CommandFail;
import com.killercats.servercore.util.Inventories;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SkullType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** /headfix status|check|fix|give|reseed|who */
public final class HeadFixCommand extends BaseCommand {

    private final HeadFixManager heads;

    public HeadFixCommand(ServerCore plugin, HeadFixManager heads) {
        super(plugin);
        this.heads = heads;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.headfix");
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (!heads.isActive() && !sub.equals("status")) {
            throw new CommandFail("headfix.inactive");
        }
        switch (sub) {
            case "status":
                status(sender);
                return;
            case "check":
                check(requirePlayer(sender));
                return;
            case "fix":
                fix(requirePlayer(sender), args);
                return;
            case "give": {
                requirePermission(sender, "servercore.headfix.give");
                Player player = requirePlayer(sender);
                if (args.length < 2) {
                    usage("/" + label + " give <player> [amount]");
                }
                int amount = args.length > 2 ? requireInt(args[2], 1, 64) : 1;
                ItemStack head = heads.headOf(args[1], amount);
                if (head == null) {
                    throw new CommandFail("headfix.unknown-player", "player", args[1]);
                }
                Inventories.giveOrDrop(player, head);
                msg(sender, "headfix.given", "player", args[1], "amount", amount);
                return;
            }
            case "reseed":
                heads.seedAll();
                msg(sender, "headfix.reseeded", "count", heads.registry().size());
                return;
            case "who": {
                List<String> eagler = new ArrayList<String>();
                List<String> java = new ArrayList<String>();
                List<String> unknown = new ArrayList<String>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    ClientType type = heads.clientOf(player);
                    (type == ClientType.EAGLER ? eagler : type == ClientType.JAVA ? java : unknown).add(player.getName());
                }
                plugin.messages().sendRaw(sender, "headfix.who", "eagler", list(eagler), "java", list(java), "unknown", list(unknown));
                return;
            }
            default:
                plugin.messages().sendRaw(sender, "headfix.help", "label", label);
        }
    }

    private static String list(List<String> names) {
        return names.isEmpty() ? "-" : names.size() + ": " + String.join(", ", names);
    }

    private void status(CommandSender sender) {
        if (!heads.isActive()) {
            msg(sender, "headfix.inactive");
            return;
        }
        HeadReflection r = heads.reflection();
        int flagTrue = 0;
        int flagOther = 0;
        int flagNone = 0;
        int offlineUuid = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            HeadReflection.ProfileData live = r.readPlayer(player);
            if (live == null || live.eaglerFlag == null) {
                flagNone++;
            } else if ("true".equalsIgnoreCase(live.eaglerFlag)) {
                flagTrue++;
            } else {
                flagOther++;
            }
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getName()).getBytes(StandardCharsets.UTF_8));
            if (offline.equals(player.getUniqueId())) {
                offlineUuid++;
            }
        }
        plugin.messages().sendRaw(sender, "headfix.status",
                "source", heads.skinSource().name().toLowerCase(Locale.ROOT),
                "remembered", heads.registry().size(),
                "items", yes(r.canEditItems()), "blocks", yes(r.canEditBlocks()), "seeding", yes(heads.seeding()),
                "flag_true", flagTrue, "flag_other", flagOther, "flag_none", flagNone,
                "offline_uuid", offlineUuid, "online", Bukkit.getOnlinePlayers().size(),
                "repaired_items", heads.itemsRepaired(), "repaired_blocks", heads.blocksRepaired(), "queued", heads.queuedChunks(),
                "server", Bukkit.getVersion());
    }

    private String yes(boolean value) {
        return plugin.messages().get(value ? "headfix.on-text" : "headfix.off-text");
    }

    @SuppressWarnings("deprecation")
    private void check(Player player) {
        HeadReflection.ProfileData data;
        String where;
        ItemStack hand = player.getItemInHand();
        if (HeadFixManager.isPlayerHead(hand)) {
            data = heads.readItem(hand);
            where = plugin.messages().get("headfix.where-hand");
        } else {
            Block block = player.getTargetBlock((Set<Material>) null, 6);
            BlockState state = block == null ? null : block.getState();
            if (!(state instanceof Skull) || ((Skull) state).getSkullType() != SkullType.PLAYER) {
                throw new CommandFail("headfix.no-head");
            }
            data = heads.readBlock((Skull) state);
            where = plugin.messages().get("headfix.where-block");
        }
        if (data == null) {
            msg(player, "headfix.no-owner");
            return;
        }
        HeadIdentity known = data.name == null ? null : heads.registry().byName(data.name);
        String idText;
        if (data.id == null) {
            idText = plugin.messages().get("headfix.id-none");
        } else if (known != null && known.uuid.equals(data.id)) {
            idText = data.id + " " + plugin.messages().get("headfix.id-matches");
        } else {
            idText = data.id + (known != null ? " " + plugin.messages().get("headfix.id-different") : "");
        }
        String url = HeadFixManager.skinUrl(data.textures);
        String texText;
        if (!data.hasTextures()) {
            texText = plugin.messages().get("headfix.tex-none");
        } else if (HeadFixManager.PLACEHOLDER.equals(data.textures) || url == null) {
            texText = plugin.messages().get("headfix.tex-placeholder");
        } else {
            texText = url + (heads.isUrlHostAllowed(data.textures) ? "" : " " + plugin.messages().get("headfix.tex-host-blocked"));
        }

        // What each client will draw, using the EaglercraftX 1.8 client's rules.
        String eagler;
        if (data.id == null) {
            eagler = plugin.messages().get("headfix.predict-steve-no-id");
        } else if (data.eaglerFlag != null) {
            eagler = plugin.messages().get("headfix.predict-live");
        } else if (url != null && heads.isUrlHostAllowed(data.textures)) {
            eagler = plugin.messages().get("headfix.predict-url");
        } else if (url != null) {
            eagler = plugin.messages().get("headfix.predict-steve-host");
        } else {
            eagler = plugin.messages().get("headfix.predict-default");
        }
        String java = url != null ? plugin.messages().get("headfix.predict-java-skin")
                : data.hasTextures() ? plugin.messages().get("headfix.predict-default")
                : plugin.messages().get("headfix.predict-java-lookup");
        boolean broken = heads.fixFor(data) != null;
        plugin.messages().sendRaw(player, "headfix.check", "where", where,
                "name", data.name == null ? "-" : data.name, "id", idText, "textures", texText,
                "flag", data.eaglerFlag == null ? "-" : data.eaglerFlag,
                "known", known == null ? plugin.messages().get("headfix.off-text") : known.name + " (" + known.client.display() + ")",
                "eagler", eagler, "java", java,
                "repair", plugin.messages().get(broken ? "headfix.repair-yes" : "headfix.repair-no"));
    }

    private void fix(Player player, String[] args) {
        String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "inv";
        switch (what) {
            case "hand": {
                ItemStack hand = player.getItemInHand();
                if (!HeadFixManager.isPlayerHead(hand)) {
                    throw new CommandFail("headfix.no-head");
                }
                boolean changed = heads.repairItem(hand);
                if (changed) {
                    player.setItemInHand(hand);
                }
                msg(player, changed ? "headfix.fixed" : "headfix.nothing-to-fix", "count", changed ? 1 : 0);
                return;
            }
            case "area": {
                int radius = args.length > 2 ? requireInt(args[2], 0, 8) : 3;
                int queued = heads.queueArea(player, radius);
                msg(player, "headfix.area-queued", "count", queued);
                return;
            }
            default: {
                int changed = heads.repairPlayer(player, true);
                msg(player, changed > 0 ? "headfix.fixed" : "headfix.nothing-to-fix", "count", changed);
            }
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return list("status", "check", "fix", "give", "reseed", "who");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("fix")) {
            return list("hand", "inv", "area");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return null;
        }
        return list();
    }
}
