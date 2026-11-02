package com.killercats.servercore.command;

import com.killercats.servercore.ServerCore;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /store - clickable link to the Tebex web store. */
public final class StoreCommand extends BaseCommand {

    public StoreCommand(ServerCore plugin) {
        super(plugin);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        String url = plugin.getConfig().getString("store.url", "");
        if (url == null || url.trim().isEmpty()) {
            throw new CommandFail("store.not-set-up");
        }
        url = url.trim();
        plugin.messages().sendRaw(sender, "store.header");
        String line = plugin.messages().get("store.link", "url", url);
        if (sender instanceof Player) {
            TextComponent component = new TextComponent(TextComponent.fromLegacyText(line));
            component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new BaseComponent[]{new TextComponent(plugin.messages().get("store.hover"))}));
            ((Player) sender).spigot().sendMessage(component);
        } else {
            sender.sendMessage(line);
        }
        plugin.messages().sendRaw(sender, "store.footer");
    }
}
