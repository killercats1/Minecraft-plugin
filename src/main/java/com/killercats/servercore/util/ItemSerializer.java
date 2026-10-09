package com.killercats.servercore.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public final class ItemSerializer {

    private ItemSerializer() {
    }

    public static String toBase64(ItemStack item) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes);
            out.writeObject(item);
            out.close();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize item", e);
        }
    }

    public static ItemStack fromBase64(String data) {
        try {
            BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
            ItemStack item = (ItemStack) in.readObject();
            in.close();
            return item;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Unable to deserialize item", e);
        }
    }
}
