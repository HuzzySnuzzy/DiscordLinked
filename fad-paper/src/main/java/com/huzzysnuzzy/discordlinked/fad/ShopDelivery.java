package com.huzzysnuzzy.discordlinked.fad;

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
 * Puts a website Shop order in a player's inventory: one chest holding the bought stacks. The proxy has already
 * taken the credits and gives them back unless this answers "ok".
 */
final class ShopDelivery {

    /** The proxy gives up after 12 seconds and refunds; never deliver anything that late. */
    private static final long DEADLINE_MILLIS = 8_000;

    record Line(String id, int amount) {
    }

    private ShopDelivery() {
    }

    /** Runs on the player's own thread; {@code reply} gets "ok" or why not. */
    static void deliver(DiscordLinkedFAD plugin, Player player, List<Line> lines, long receivedAt,
                        java.util.function.Consumer<String> reply) {
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
            if (player.getInventory().firstEmpty() == -1) {
                reply.accept("full");
                return;
            }
            ItemStack chest = new ItemStack(Material.CHEST);
            chest.editMeta(BlockStateMeta.class, meta -> {
                Chest state = (Chest) meta.getBlockState();
                Inventory inventory = state.getBlockInventory();
                for (int slot = 0; slot < stacks.size() && slot < inventory.getSize(); slot++) {
                    inventory.setItem(slot, stacks.get(slot));
                }
                meta.setBlockState(state);
                meta.displayName(Component.text("Shop order", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(Component.text(stacks.size() + (stacks.size() == 1 ? " stack" : " stacks")
                        + " bought on the website", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            });
            if (!player.getInventory().addItem(chest).isEmpty()) {
                reply.accept("full");
                return;
            }
            reply.accept("ok");
        }, () -> reply.accept("offline"));
    }
}
