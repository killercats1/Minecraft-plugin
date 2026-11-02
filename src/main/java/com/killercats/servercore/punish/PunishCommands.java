package com.killercats.servercore.punish;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.CommandFail;
import com.killercats.servercore.punish.Punishment.Type;
import com.killercats.servercore.punish.PunishmentManager.Preset;
import com.killercats.servercore.punish.PunishmentManager.Step;
import com.killercats.servercore.util.Text;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** /punish, /warn, /unpunish, /phistory and /alts. */
public final class PunishCommands {

    private PunishCommands() {
    }

    private static PunishmentManager manager(ServerCore plugin) {
        if (!plugin.punishments().enabled()) {
            throw new CommandFail("punish.disabled");
        }
        return plugin.punishments();
    }

    private static String permissionFor(Type type, long duration) {
        return new Step(type, duration).permission();
    }

    /**
     * /punish <player> - menu with preset reasons.
     * /punish <player> <preset> [note] - apply a preset's next step.
     * /punish <player> <warn|kick|mute|tempban|ban> [duration] <reason> - manual punishment.
     */
    public static final class Punish extends BaseCommand {

        public Punish(ServerCore plugin) {
            super(plugin);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            requirePermission(sender, "servercore.punish");
            PunishmentManager manager = manager(plugin);
            if (args.length == 0) {
                usage("/" + label + " <player> [preset | warn|kick|mute|tempban|ban [duration] <reason>]");
            }
            String name = args[0];
            if (args.length == 1) {
                Player staff = requirePlayer(sender);
                openMenu(staff, name, null);
                return;
            }
            String second = args[1].toLowerCase(Locale.ROOT);
            Preset preset = manager.presets().get(second);
            if (preset != null) {
                String note = Text.join(args, 2);
                manager.resolve(name).thenCompose(target -> target == null
                        ? CompletableFuture.completedFuture(new Object[]{null, 0})
                        : manager.offences(target.uuid, preset.id).thenApply(count -> new Object[]{target, count}))
                        .whenComplete((result, error) -> plugin.sync(() -> {
                            if (error != null) {
                                msg(sender, "general.error");
                                return;
                            }
                            PunishmentManager.Target target = (PunishmentManager.Target) result[0];
                            if (target == null) {
                                msg(sender, "general.player-not-found", "player", name);
                                return;
                            }
                            Step step = preset.step((Integer) result[1]);
                            if (!sender.hasPermission(step.permission())) {
                                msg(sender, "punish.no-permission-step", "action", step.describe());
                                return;
                            }
                            String reason = note.isEmpty() ? preset.reason : preset.reason + " (" + note + ")";
                            manager.apply(sender, target, step.type, step.duration, reason, preset.id);
                        }));
                return;
            }
            Type type = Type.parse(second);
            if (type == null) {
                throw new CommandFail("punish.unknown-type", "input", args[1], "presets", String.join(", ", manager.presets().keySet()));
            }
            int reasonStart = 2;
            long duration = 0;
            if (type == Type.BAN || type == Type.MUTE) {
                if (args.length > 2 && TimeUtil.parseDuration(args[2]) > 0) {
                    duration = TimeUtil.parseDuration(args[2]);
                    reasonStart = 3;
                } else if (args.length > 2 && (args[2].equalsIgnoreCase("perm") || args[2].equalsIgnoreCase("permanent"))) {
                    reasonStart = 3;
                } else if (second.startsWith("temp")) {
                    usage("/" + label + " <player> " + second + " <duration, e.g. 7d> <reason>");
                }
            }
            String reason = Text.join(args, reasonStart);
            if (reason.isEmpty() && type != Type.KICK) {
                usage("/" + label + " <player> " + second + (type == Type.BAN || type == Type.MUTE ? " [duration|perm]" : "") + " <reason>");
            }
            requirePermission(sender, permissionFor(type, duration));
            manager.punish(sender, name, type, duration, reason, null);
        }

        private void openMenu(Player staff, String name, String note) {
            PunishmentManager manager = plugin.punishments();
            if (manager.presets().isEmpty()) {
                throw new CommandFail("punish.no-presets");
            }
            manager.resolve(name).whenComplete((target, error) -> {
                if (error != null || target == null) {
                    plugin.sync(() -> msg(staff, error != null ? "general.error" : "general.player-not-found", "player", name));
                    return;
                }
                List<CompletableFuture<Integer>> counts = new ArrayList<CompletableFuture<Integer>>();
                List<String> ids = new ArrayList<String>(manager.presets().keySet());
                for (String id : ids) {
                    counts.add(manager.offences(target.uuid, id));
                }
                CompletableFuture.allOf(counts.toArray(new CompletableFuture[0])).whenComplete((ignored, err) -> plugin.sync(() -> {
                    if (err != null) {
                        msg(staff, "general.error");
                        return;
                    }
                    if (!staff.isOnline()) {
                        return;
                    }
                    Map<String, Integer> offences = new HashMap<String, Integer>();
                    for (int i = 0; i < ids.size(); i++) {
                        offences.put(ids.get(i), counts.get(i).join());
                    }
                    new PunishMenu(plugin, staff, target, offences, note).open(staff);
                }));
            });
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                return null;
            }
            if (args.length == 2) {
                List<String> options = new ArrayList<String>(plugin.punishments().presets().keySet());
                options.addAll(list("warn", "kick", "mute", "tempban", "ban"));
                return options;
            }
            if (args.length == 3 && Type.parse(args[1]) != null && (Type.parse(args[1]) == Type.BAN || Type.parse(args[1]) == Type.MUTE)) {
                return list("30m", "1h", "1d", "3d", "7d", "30d", "perm");
            }
            return list();
        }
    }

    /** /warn <player> <reason> */
    public static final class Warn extends BaseCommand {

        public Warn(ServerCore plugin) {
            super(plugin);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            requirePermission(sender, "servercore.punish");
            PunishmentManager manager = manager(plugin);
            if (args.length < 2) {
                usage("/" + label + " <player> <reason>");
            }
            manager.punish(sender, args[0], Type.WARN, 0, Text.join(args, 1), null);
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 ? null : list();
        }
    }

    /** /unpunish <player> <ban|mute> [note] */
    public static final class Unpunish extends BaseCommand {

        public Unpunish(ServerCore plugin) {
            super(plugin);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            PunishmentManager manager = manager(plugin);
            if (args.length < 2) {
                usage("/" + label + " <player> <ban|mute> [note]");
            }
            Type type = Type.parse(args[1]);
            if (type != Type.BAN && type != Type.MUTE) {
                usage("/" + label + " <player> <ban|mute> [note]");
            }
            requirePermission(sender, type == Type.BAN ? "servercore.punish.unban" : "servercore.punish.unmute");
            manager.pardon(sender, args[0], type, Text.join(args, 2));
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 ? null : args.length == 2 ? list("ban", "mute") : list();
        }
    }

    /** /phistory <player> [page] */
    public static final class History extends BaseCommand {

        private static final int PAGE_SIZE = 8;

        public History(ServerCore plugin) {
            super(plugin);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            requirePermission(sender, "servercore.punish.history");
            PunishmentManager manager = manager(plugin);
            if (args.length < 1) {
                usage("/" + label + " <player> [page]");
            }
            int page = args.length > 1 ? requireInt(args[1], 1, 1000) : 1;
            String name = args[0];
            manager.resolve(name).thenCompose(target -> target == null
                    ? CompletableFuture.completedFuture((List<Punishment>) null)
                    : manager.history(target.uuid)).whenComplete((list, error) -> plugin.sync(() -> {
                if (error != null) {
                    msg(sender, "general.error");
                    return;
                }
                if (list == null) {
                    msg(sender, "general.player-not-found", "player", name);
                    return;
                }
                int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                int current = Math.min(page, pages);
                plugin.messages().sendRaw(sender, "punish.history-header", "player", name, "count", list.size(), "page", current, "pages", pages);
                if (list.isEmpty()) {
                    plugin.messages().sendRaw(sender, "punish.history-empty");
                    return;
                }
                long now = System.currentTimeMillis();
                for (int i = (current - 1) * PAGE_SIZE; i < Math.min(list.size(), current * PAGE_SIZE); i++) {
                    Punishment p = list.get(i);
                    String status;
                    if (p.removedBy != null) {
                        status = plugin.messages().get("punish.status-removed", "staff", p.removedBy);
                    } else if (p.inForce(now)) {
                        status = plugin.messages().get("punish.status-active");
                    } else {
                        status = "";
                    }
                    String length = p.type == Type.BAN || p.type == Type.MUTE
                            ? (p.permanent() ? plugin.messages().get("punish.permanent") : TimeUtil.formatDuration(p.expires - p.created)) : "";
                    plugin.messages().sendRaw(sender, "punish.history-line", "id", p.id, "date", TimeUtil.formatDate(p.created),
                            "type", Text.capitalize(p.type.name()), "length", length, "reason", p.reason, "staff", p.staffName, "status", status);
                }
            }));
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 ? null : list();
        }
    }

    /** /alts <player> */
    public static final class Alts extends BaseCommand {

        public Alts(ServerCore plugin) {
            super(plugin);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            requirePermission(sender, "servercore.punish.alts");
            PunishmentManager manager = manager(plugin);
            if (args.length < 1) {
                usage("/" + label + " <player>");
            }
            String name = args[0];
            manager.resolve(name).thenCompose(target -> target == null
                    ? CompletableFuture.completedFuture((List<String[]>) null)
                    : manager.alts(target.uuid)).whenComplete((alts, error) -> plugin.sync(() -> {
                if (error != null) {
                    msg(sender, "general.error");
                    return;
                }
                if (alts == null) {
                    msg(sender, "general.player-not-found", "player", name);
                    return;
                }
                if (alts.isEmpty()) {
                    msg(sender, "punish.alts-none", "player", name);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (String[] alt : alts) {
                    if (sb.length() > 0) {
                        sb.append(plugin.messages().get("punish.alts-separator"));
                    }
                    sb.append(plugin.messages().get("1".equals(alt[1]) ? "punish.alts-banned" : "punish.alts-clean", "player", alt[0]));
                }
                msg(sender, "punish.alts-list", "player", name, "count", alts.size(), "accounts", sb.toString());
            }));
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 ? null : list();
        }
    }
}
