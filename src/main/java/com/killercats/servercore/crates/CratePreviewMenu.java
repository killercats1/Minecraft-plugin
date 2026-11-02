package com.killercats.servercore.crates;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.crates.Crate.Reward;
import com.killercats.servercore.gui.Menu;
import com.killercats.servercore.util.ItemBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shows every reward of a crate with its chance. */
public final class CratePreviewMenu extends Menu {

    public CratePreviewMenu(ServerCore plugin, Crate crate) {
        super(plugin.messages().get("crates.preview-title", "crate", crate.name), Math.max(1, Math.min(6, (crate.rewards.size() + 8) / 9)));
        int slot = 0;
        for (Reward reward : crate.rewards) {
            if (slot >= inventory.getSize()) {
                break;
            }
            List<String> lore = new ArrayList<String>();
            lore.add(plugin.messages().get("crates.preview-chance", "chance", String.format(Locale.US, "%.1f", crate.chance(reward))));
            set(slot++, new ItemBuilder(reward.display).addLore(lore).build(), null);
        }
    }

    /** /crate: every crate with the player's key count; click to open. */
    public static final class ListMenu extends Menu {

        public ListMenu(ServerCore plugin, Map<String, Integer> keys) {
            super(plugin.messages().get("crates.menu-title"), Math.max(1, Math.min(6, (plugin.crates().crates().size() + 8) / 9)));
            int slot = 0;
            for (Crate crate : plugin.crates().crates().values()) {
                if (slot >= inventory.getSize()) {
                    break;
                }
                int count = keys.containsKey(crate.id) ? keys.get(crate.id) : 0;
                List<String> lore = plugin.messages().getList("crates.menu-lore", "keys", count);
                set(slot++, new ItemBuilder(crate.icon, Math.max(1, Math.min(64, count)), (short) 0).name(crate.name).lore(lore).build(),
                        (player, event) -> {
                            boolean preview = event.isRightClick();
                            player.closeInventory();
                            // Open the next menu on the next tick; opening one inside a click event is unreliable on 1.8.
                            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                                if (!player.isOnline()) {
                                    return;
                                }
                                if (preview) {
                                    new CratePreviewMenu(plugin, crate).open(player);
                                } else {
                                    plugin.crates().open(player, crate);
                                }
                            });
                        });
            }
        }
    }
}
