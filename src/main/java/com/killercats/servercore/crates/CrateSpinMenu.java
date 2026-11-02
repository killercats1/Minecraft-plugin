package com.killercats.servercore.crates;

import com.killercats.servercore.ServerCore;
import com.killercats.servercore.crates.Crate.Reward;
import com.killercats.servercore.gui.Menu;
import com.killercats.servercore.util.ItemBuilder;
import com.killercats.servercore.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * The opening animation: items scroll along the middle row and slow down until the (already chosen)
 * reward stops under the arrow. Closing the menu early skips straight to the reward.
 */
public final class CrateSpinMenu extends Menu {

    private static final int STEPS = 28;

    private final ServerCore plugin;
    private final Crate crate;
    private final Reward reward;
    private final Runnable onFinish;
    private final ItemStack[] row = new ItemStack[9];
    private boolean finished;
    private int step;
    private BukkitTask task;

    CrateSpinMenu(ServerCore plugin, Crate crate, Reward reward, Runnable onFinish) {
        super(crate.name, 3);
        this.plugin = plugin;
        this.crate = crate;
        this.reward = reward;
        this.onFinish = onFinish;
        ItemStack glass = ItemBuilder.filler();
        for (int i = 0; i < 27; i++) {
            set(i, glass, null);
        }
        ItemStack pointer = new ItemBuilder(Material.HOPPER).name("&e▼").build();
        set(4, pointer, null);
        set(22, new ItemBuilder(Material.REDSTONE_TORCH_ON).name("&e▲").build(), null);
        for (int i = 0; i < 9; i++) {
            row[i] = crate.randomForDisplay().display;
        }
        draw();
    }

    void start(Player player) {
        open(player);
        scheduleNext(player);
    }

    private void scheduleNext(Player player) {
        // Fast at first, slower near the end.
        long delay = step < 14 ? 2 : step < 22 ? 3 : step < 26 ? 5 : 8;
        task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (finished) {
                return;
            }
            step++;
            System.arraycopy(row, 1, row, 0, 8);
            // The item that will be in the middle (slot 4 of the row) when the last step is done.
            int stepsLeft = STEPS - step;
            row[8] = stepsLeft == 4 ? reward.display : crate.randomForDisplay().display;
            draw();
            Sounds.play(player, "CLICK");
            if (step >= STEPS) {
                complete(player);
            } else {
                scheduleNext(player);
            }
        }, delay);
    }

    private void draw() {
        for (int i = 0; i < 9; i++) {
            set(9 + i, row[i], null);
        }
    }

    private void complete(Player player) {
        if (finished) {
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
        }
        onFinish.run();
        // Leave the result visible for a moment.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == this) {
                player.closeInventory();
            }
        }, 30L);
    }

    @Override
    public void onClose(Player player, InventoryCloseEvent event) {
        complete(player);
    }
}
