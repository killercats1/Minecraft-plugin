package com.killercats.servercore.vote;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.command.BaseCommand;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class VoteCommand extends BaseCommand {

    private final VoteManager votes;
    private final VotifierHook votifier;

    public VoteCommand(ServerCore plugin, VoteManager votes, VotifierHook votifier) {
        super(plugin);
        this.votes = votes;
        this.votifier = votifier;
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        requirePermission(sender, "servercore.vote");
        if (!votes.enabled()) {
            msg(sender, "vote.disabled");
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("top")) {
            votes.topThisMonth(10).whenComplete((top, error) -> plugin.sync(() -> {
                if (error != null) {
                    msg(sender, "general.error");
                    return;
                }
                plugin.messages().sendRaw(sender, "vote.top-header");
                if (top.isEmpty()) {
                    plugin.messages().sendRaw(sender, "general.nothing-here");
                }
                int rank = 1;
                for (Object[] row : top) {
                    plugin.messages().sendRaw(sender, "vote.top-line", "rank", rank++, "player", row[0], "votes", row[1]);
                }
            }));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("test")) {
            requirePermission(sender, "servercore.vote.admin");
            if (args.length < 2) {
                usage("vote test <player>");
            }
            msg(sender, "vote.test", "player", args[1], "votifier", votifier.isHooked() ? "hooked" : "NOT installed");
            votes.handleVote(args[1], "test");
            return;
        }
        List<String[]> links = votes.links();
        plugin.messages().sendRaw(sender, "vote.header");
        if (links.isEmpty()) {
            plugin.messages().sendRaw(sender, "vote.no-links");
        }
        for (String[] link : links) {
            String line = plugin.messages().get("vote.link", "name", link[0], "url", link[1]);
            if (sender instanceof Player) {
                // 1.8-era chat API only: the server's bundled chat library is older than the one we compile against.
                TextComponent component = new TextComponent(TextComponent.fromLegacyText(line));
                component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link[1]));
                component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new BaseComponent[]{new TextComponent(plugin.messages().get("vote.link-hover"))}));
                ((Player) sender).spigot().sendMessage(component);
            } else {
                sender.sendMessage(line);
            }
        }
        if (sender instanceof Player) {
            votes.totalVotes(sender.getName()).whenComplete((count, error) -> plugin.sync(() -> {
                if (error == null && ((Player) sender).isOnline()) {
                    plugin.messages().sendRaw(sender, "vote.footer", "votes", count);
                }
            }));
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return sender.hasPermission("servercore.vote.admin") ? list("top", "test") : list("top");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return null;
        }
        return Collections.emptyList();
    }
}
