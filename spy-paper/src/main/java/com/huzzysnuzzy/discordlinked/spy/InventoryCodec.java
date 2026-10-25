package com.huzzysnuzzy.discordlinked.spy;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Exact copies of a player's items (main inventory, armour, offhand, ender chest) using Paper's own item
 * serialization, so names, enchantments, NBT and shulker contents all survive a restore.
 */
final class InventoryCodec {

    private InventoryCodec() {
    }

    /** Call on the player's own thread. */
    static byte[] save(Player player) {
        PlayerInventory inventory = player.getInventory();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeItems(out, inventory.getStorageContents());
            writeItems(out, inventory.getArmorContents());
            writeItems(out, new ItemStack[] {inventory.getItemInOffHand()});
            writeItems(out, player.getEnderChest().getContents());
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replaces the player's items with a saved copy. Call on the player's own thread. */
    static void apply(Player player, byte[] data, boolean includeEnderChest) throws IOException {
        ItemStack[] storage;
        ItemStack[] armor;
        ItemStack[] offhand;
        ItemStack[] ender;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            storage = readItems(in);
            armor = readItems(in);
            offhand = readItems(in);
            ender = readItems(in);
        }
        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(fit(storage, inventory.getStorageContents().length));
        inventory.setArmorContents(fit(armor, inventory.getArmorContents().length));
        inventory.setItemInOffHand(offhand.length > 0 ? offhand[0] : null);
        if (includeEnderChest) {
            player.getEnderChest().setContents(fit(ender, player.getEnderChest().getSize()));
        }
        player.updateInventory();
    }

    static int countStacks(Player player) {
        int count = 0;
        for (ItemStack[] group : new ItemStack[][] {player.getInventory().getStorageContents(),
                player.getInventory().getArmorContents(), {player.getInventory().getItemInOffHand()},
                player.getEnderChest().getContents()}) {
            for (ItemStack stack : group) {
                if (stack != null && !stack.getType().isAir()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void writeItems(DataOutputStream out, ItemStack[] items) throws IOException {
        out.writeInt(items.length);
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                out.writeInt(0);
            } else {
                byte[] bytes = item.serializeAsBytes();
                out.writeInt(bytes.length);
                out.write(bytes);
            }
        }
    }

    private static ItemStack[] readItems(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 256) {
            throw new IOException("bad item count " + count);
        }
        ItemStack[] items = new ItemStack[count];
        for (int i = 0; i < count; i++) {
            int length = in.readInt();
            if (length < 0 || length > 4 << 20) {
                throw new IOException("bad item size " + length);
            }
            if (length > 0) {
                items[i] = ItemStack.deserializeBytes(in.readNBytes(length));
            }
        }
        return items;
    }

    private static ItemStack[] fit(ItemStack[] items, int size) {
        ItemStack[] result = new ItemStack[size];
        System.arraycopy(items, 0, result, 0, Math.min(size, items.length));
        return result;
    }
}
