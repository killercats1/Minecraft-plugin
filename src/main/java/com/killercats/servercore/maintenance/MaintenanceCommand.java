package com.killercats.servercore.maintenance;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.util.Text;
import com.killercats.servercore.util.TimeUtil;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

public final class MaintenanceCommand extends BaseCommand {

    public MaintenanceCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.maintenance");
        MaintenanceManager m = plugin.maintenance();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "on":
            case "enable": {
                if (m.isEnabled()) {
                    msg(sender, "maintenance.already-enabled");
                    return;
                }
                long duration = -1;
                int reasonStart = 1;
                if (args.length > 1) {
                    duration = TimeUtil.parseDuration(args[1]);
                    if (duration > 0) {
                        reasonStart = 2;
                    }
                }
                m.cancelSchedule();
                m.enable(Text.join(args, reasonStart), duration, sender.getName());
                msg(sender, "maintenance.enabled", "remaining", m.remainingText());
                return;
            }
            case "off":
            case "disable":
                if (!m.isEnabled()) {
                    msg(sender, "maintenance.already-disabled");
                    return;
                }
                m.disable(sender.getName());
                msg(sender, "maintenance.disabled");
                return;
            case "toggle":
                execute(sender, label, new String[]{m.isEnabled() ? "off" : "on"});
                return;
            case "schedule": {
                if (args.length < 2) {
                    usage("/" + label + " schedule <delay> [duration] [reason]");
                }
                long delay = TimeUtil.parseDuration(args[1]);
                if (delay <= 0) {
                    msg(sender, "general.invalid-duration", "input", args[1]);
                    return;
                }
                long duration = -1;
                int reasonStart = 2;
                if (args.length > 2) {
                    duration = TimeUtil.parseDuration(args[2]);
                    if (duration > 0) {
                        reasonStart = 3;
                    }
                }
                m.schedule(delay, duration, Text.join(args, reasonStart));
                msg(sender, "maintenance.scheduled", "time", TimeUtil.formatDuration(delay));
                return;
            }
            case "cancel":
                msg(sender, m.cancelSchedule() ? "maintenance.schedule-cancelled" : "maintenance.no-schedule");
                return;
            case "reason":
                if (args.length < 2) {
                    usage("/" + label + " reason <text>");
                }
                m.setReason(Text.join(args, 1));
                msg(sender, "maintenance.reason-set", "reason", m.reason());
                return;
            case "extend": {
                if (args.length < 2 || TimeUtil.parseDuration(args[1]) <= 0) {
                    usage("/" + label + " extend <duration>");
                }
                if (!m.isEnabled()) {
                    msg(sender, "maintenance.already-disabled");
                    return;
                }
                m.extend(TimeUtil.parseDuration(args[1]));
                msg(sender, "maintenance.extended", "remaining", m.remainingText());
                return;
            }
            case "add":
                if (args.length < 2) {
                    usage("/" + label + " add <player>");
                }
                msg(sender, m.addWhitelist(args[1]) ? "maintenance.whitelist-added" : "maintenance.whitelist-already", "player", args[1]);
                return;
            case "remove":
                if (args.length < 2) {
                    usage("/" + label + " remove <player>");
                }
                msg(sender, m.removeWhitelist(args[1]) ? "maintenance.whitelist-removed" : "maintenance.whitelist-missing", "player", args[1]);
                return;
            case "list":
                msg(sender, "maintenance.whitelist-list", "count", m.whitelist().size(),
                        "players", m.whitelist().isEmpty() ? "-" : String.join(", ", m.whitelist()));
                msg(sender, "maintenance.ranks-list", "ranks", m.allowedRanks().isEmpty() ? "-" : String.join(", ", m.allowedRanks()));
                return;
            case "status":
                if (m.isEnabled()) {
                    plugin.messages().sendRaw(sender, "maintenance.status-on", "reason", m.reason(), "remaining", m.remainingText(),
                            "actor", m.enabledBy(), "since", TimeUtil.formatDate(m.startedAt()));
                } else {
                    plugin.messages().sendRaw(sender, "maintenance.status-off");
                }
                if (m.scheduledStart() > 0) {
                    msg(sender, "maintenance.status-scheduled", "time", TimeUtil.formatDuration(m.scheduledStart() - System.currentTimeMillis()));
                }
                return;
            default:
                plugin.messages().sendRaw(sender, "maintenance.help", "label", label);
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return list("on", "off", "toggle", "status", "schedule", "cancel", "reason", "extend", "add", "remove", "list");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("add")) {
                return null;
            }
            if (sub.equals("remove")) {
                return new java.util.ArrayList<String>(plugin.maintenance().whitelist());
            }
            if (sub.equals("on") || sub.equals("schedule") || sub.equals("extend")) {
                return list("30m", "1h", "2h", "1d");
            }
        }
        return list();
    }
}
