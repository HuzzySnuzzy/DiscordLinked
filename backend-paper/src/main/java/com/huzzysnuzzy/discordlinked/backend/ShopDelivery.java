package com.huzzysnuzzy.discordlinked.backend;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts a website Shop order in a player's inventory: one chest (or shulker box) holding the bought stacks, or the
 * stacks themselves ("direct": what doesn't fit is dropped at the player's feet). The proxy has already taken the
 * credits and gives them back unless this answers "ok" (or "ok:dropped:N" when N stacks were dropped).
 */
final class ShopDelivery {

    /** The proxy gives up after 12 seconds and refunds; never deliver anything that late. */
    private static final long DEADLINE_MILLIS = 8_000;

    record Line(String id, int amount) {
    }

    private ShopDelivery() {
    }

    /** Runs on the player's own thread; {@code reply} gets "ok" or why not. {@code container}: chest, shulker or direct. */
    static void deliver(DiscordLinkedBackend plugin, Player player, List<Line> lines, String title, String container,
                        long receivedAt, java.util.function.Consumer<String> reply) {
        player.getScheduler().run(plugin, task -> {
            if (System.currentTimeMillis() - receivedAt > DEADLINE_MILLIS) {
                reply.accept("expired");
                return;
            }
            List<ItemStack> stacks = new ArrayList<>();
            for (Line line : lines) {
                Material material = Material.matchMaterial(line.id());
                if (material == null || !material.isItem() || material.isAir()) {
                    reply.accept("unknown:" + line.id());
                    return;
                }
                if (line.amount() < 1 || line.amount() > material.getMaxStackSize()) {
                    reply.accept("stack:" + line.id());
                    return;
                }
                stacks.add(new ItemStack(material, line.amount()));
            }
            if (container.equals("direct")) {
                // Into the inventory; what doesn't fit is dropped where they stand.
                java.util.Map<Integer, ItemStack> left = player.getInventory().addItem(stacks.toArray(ItemStack[]::new));
                for (ItemStack stack : left.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), stack);
                }
                reply.accept(left.isEmpty() ? "ok" : "ok:dropped:" + left.size());
                return;
            }
            if (player.getInventory().firstEmpty() == -1) {
                reply.accept("full");
                return;
            }
            boolean shulker = container.equals("shulker");
            ItemStack chest = new ItemStack(shulker ? Material.SHULKER_BOX : Material.CHEST);
            chest.editMeta(BlockStateMeta.class, meta -> {
                org.bukkit.block.Container state = (org.bukkit.block.Container) meta.getBlockState();
                Inventory inventory = state instanceof Chest c ? c.getBlockInventory() : state.getInventory();
                for (int slot = 0; slot < stacks.size() && slot < inventory.getSize(); slot++) {
                    inventory.setItem(slot, stacks.get(slot));
                }
                meta.setBlockState(state);
                boolean gift = title.startsWith("Gift");
                String name = title.length() > 64 ? title.substring(0, 64) : title;
                meta.displayName(Component.text(name, gift ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(Component.text(stacks.size() + (stacks.size() == 1 ? " stack" : " stacks")
                        + (gift ? " sent from the website" : " bought on the website"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
            });
            if (!player.getInventory().addItem(chest).isEmpty()) {
                reply.accept("full");
                return;
            }
            reply.accept("ok");
        }, () -> reply.accept("offline"));
    }
}
