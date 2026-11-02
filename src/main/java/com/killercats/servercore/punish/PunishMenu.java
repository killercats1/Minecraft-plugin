package com.killercats.servercore.punish;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.gui.Menu;
import com.killercats.servercore.punish.PunishmentManager.Preset;
import com.killercats.servercore.punish.PunishmentManager.Step;
import com.killercats.servercore.punish.PunishmentManager.Target;
import com.killercats.servercore.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /punish <player>: one button per preset, showing what the next offence will get. */
public final class PunishMenu extends Menu {

    private final ServerCore plugin;
    private final Target target;
    private final String extraNote;

    /**
     * @param offences previous offences per preset id
     */
    public PunishMenu(ServerCore plugin, Player staff, Target target, Map<String, Integer> offences, String extraNote) {
        super(plugin.messages().get("punish.menu-title", "player", target.name), rows(plugin.punishments().presets().size()));
        this.plugin = plugin;
        this.target = target;
        this.extraNote = extraNote;
        int slot = 0;
        for (Preset preset : plugin.punishments().presets().values()) {
            if (slot >= inventory.getSize() - 9) {
                break;
            }
            int previous = offences.containsKey(preset.id) ? offences.get(preset.id) : 0;
            Step step = preset.step(previous);
            boolean allowed = staff.hasPermission(step.permission());
            List<String> lore = new ArrayList<String>();
            lore.add(plugin.messages().get("punish.menu-reason", "reason", preset.reason));
            lore.add(plugin.messages().get("punish.menu-offences", "count", previous));
            lore.add(plugin.messages().get("punish.menu-next", "action", step.describe()));
            lore.add("");
            lore.add(plugin.messages().get(allowed ? "punish.menu-click" : "punish.menu-locked"));
            set(slot++, new ItemBuilder(allowed ? preset.icon : Material.BARRIER).name(preset.name).lore(lore).hideFlags().build(),
                    allowed ? (player, event) -> {
                        player.closeInventory();
                        String reason = extraNote == null || extraNote.isEmpty() ? preset.reason : preset.reason + " (" + extraNote + ")";
                        plugin.punishments().apply(player, target, step.type, step.duration, reason, preset.id);
                    } : null);
        }
        int historySlot = inventory.getSize() - 1;
        set(historySlot, new ItemBuilder(Material.BOOK).name(plugin.messages().get("punish.menu-history")).build(), (player, event) -> {
            player.closeInventory();
            player.performCommand("phistory " + target.name);
        });
        if (staff.hasPermission("servercore.punish.alts")) {
            set(historySlot - 1, new ItemBuilder(Material.SKULL_ITEM, 1, (short) 3).name(plugin.messages().get("punish.menu-alts")).build(),
                    (player, event) -> {
                        player.closeInventory();
                        player.performCommand("alts " + target.name);
                    });
        }
    }

    private static int rows(int presets) {
        // Presets plus a bottom row for the history/alts buttons.
        return Math.max(2, Math.min(6, (presets + 8) / 9 + 1));
    }

    public Target target() {
        return target;
    }

    public String note() {
        return extraNote;
    }
}
