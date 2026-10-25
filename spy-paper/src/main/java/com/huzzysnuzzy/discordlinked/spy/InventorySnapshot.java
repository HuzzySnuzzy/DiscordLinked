package com.huzzysnuzzy.discordlinked.spy;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/** Turns a player's inventory, armour, offhand, ender chest and basic stats into JSON for the website. */
final class InventorySnapshot {

    private static final int MAX_LORE_LINES = 12;

    private InventorySnapshot() {
    }

    /** Call on the player's own thread. */
    static JsonObject of(Player player) {
        PlayerInventory inventory = player.getInventory();
        JsonObject json = new JsonObject();
        json.addProperty("mcVersion", Bukkit.getMinecraftVersion());
        json.addProperty("health", Math.round(player.getHealth() * 10) / 10.0);
        json.addProperty("food", player.getFoodLevel());
        json.addProperty("level", player.getLevel());
        json.addProperty("gameMode", player.getGameMode().name());
        Location loc = player.getLocation();
        json.addProperty("world", loc.getWorld().getName());
        json.addProperty("x", loc.getBlockX());
        json.addProperty("y", loc.getBlockY());
        json.addProperty("z", loc.getBlockZ());
        json.addProperty("selectedSlot", inventory.getHeldItemSlot());
        json.add("storage", items(inventory.getStorageContents(), true));
        json.add("armor", items(inventory.getArmorContents(), true));
        json.add("offhand", item(inventory.getItemInOffHand(), true));
        json.add("enderChest", items(player.getEnderChest().getContents(), true));
        return json;
    }

    private static JsonArray items(ItemStack[] stacks, boolean withContents) {
        JsonArray array = new JsonArray();
        for (ItemStack stack : stacks) {
            array.add(item(stack, withContents));
        }
        return array;
    }

    private static com.google.gson.JsonElement item(ItemStack stack, boolean withContents) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("id", stack.getType().getKey().toString());
        json.addProperty("amount", stack.getAmount());
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta.hasDisplayName()) {
                json.addProperty("name", plain(meta.displayName()));
            }
            List<Component> lore = meta.lore();
            if (lore != null && !lore.isEmpty()) {
                JsonArray lines = new JsonArray();
                lore.stream().limit(MAX_LORE_LINES).map(InventorySnapshot::plain).forEach(lines::add);
                json.add("lore", lines);
            }
            Map<Enchantment, Integer> enchants = meta.getEnchants();
            if (!enchants.isEmpty()) {
                JsonArray list = new JsonArray();
                enchants.forEach((enchantment, level) -> {
                    JsonObject e = new JsonObject();
                    e.addProperty("id", enchantment.getKey().getKey());
                    e.addProperty("level", level);
                    list.add(e);
                });
                json.add("enchantments", list);
            }
            int maxDurability = stack.getType().getMaxDurability();
            if (maxDurability > 0 && meta instanceof Damageable damageable) {
                json.addProperty("durability", maxDurability - damageable.getDamage());
                json.addProperty("maxDurability", maxDurability);
            }
            // Shulker boxes can hide a lot, so show what's inside (one level deep).
            if (withContents && meta instanceof BlockStateMeta blockMeta && blockMeta.hasBlockState()
                    && blockMeta.getBlockState() instanceof ShulkerBox box) {
                json.add("contents", items(box.getInventory().getContents(), false));
            }
        }
        return json;
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
