package com.huzzysnuzzy.discordlinked.fad;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The /sell menu: a double chest where players put what they want to sell. Closing it sells everything that can be
 * sold and gives the rest back. The bottom row shows what it's all worth and has Sell and Cancel buttons.
 */
final class SellMenu implements InventoryHolder {

    /** Slots players can put items in; the last row is the menu's own. */
    static final int ITEM_SLOTS = 45;
    private static final int SIZE = 54;
    private static final int CANCEL_SLOT = 45;
    private static final int WORTH_SLOT = 49;
    private static final int SELL_SLOT = 53;

    private final DiscordLinkedFAD plugin;
    private final Inventory inventory;
    private final NamespacedKey menuKey;
    private boolean cancelled;
    /** The Sell button was clicked (closing it that way counts as the plugin closing it). */
    private boolean sellNow;
    private boolean updateQueued;

    private SellMenu(DiscordLinkedFAD plugin) {
        this.plugin = plugin;
        this.menuKey = new NamespacedKey(plugin, "menu");
        this.inventory = Bukkit.createInventory(this, SIZE, plugin.settings().message("sell-title"));
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = ITEM_SLOTS; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(CANCEL_SLOT, button(Material.BARRIER, plugin.settings().message("sell-cancel"),
                List.of(plugin.settings().message("sell-cancel-lore"))));
        inventory.setItem(SELL_SLOT, button(Material.LIME_DYE, plugin.settings().message("sell-button"),
                List.of(plugin.settings().message("sell-button-lore"))));
        updateWorth();
    }

    static void open(DiscordLinkedFAD plugin, Player player) {
        player.openInventory(new SellMenu(plugin).inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            meta.lore(lore.stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                    .toList());
            // Marks the menu's own items, so they can never end up sold or in someone's inventory.
            meta.getPersistentDataContainer().set(menuKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    /** The running total on the bottom row. */
    private void updateWorth() {
        updateQueued = false;
        SellPrices prices = plugin.sellPrices();
        long worth = 0;
        int items = 0;
        int unsellable = 0;
        for (int slot = 0; slot < ITEM_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (prices.sellable(stack)) {
                worth += prices.worth(stack);
                items += stack.getAmount();
            } else {
                unsellable += stack.getAmount();
            }
        }
        FadSettings settings = plugin.settings();
        List<Component> lore = new ArrayList<>();
        lore.add(settings.message("sell-worth-lore", Placeholder.unparsed("items", String.format(java.util.Locale.ROOT, "%,d", items))));
        if (unsellable > 0) {
            lore.add(settings.message("sell-unsellable-lore", Placeholder.unparsed("count", String.format(java.util.Locale.ROOT, "%,d", unsellable))));
        }
        inventory.setItem(WORTH_SLOT, button(worth > 0 ? Material.EMERALD : Material.GLASS_BOTTLE,
                settings.message("sell-worth", Placeholder.unparsed("amount", prices.formatTotal(worth))), lore));
    }

    private void queueUpdate(Player player) {
        if (updateQueued) {
            return;
        }
        updateQueued = true;
        // After the click has moved the items.
        player.getScheduler().run(plugin, task -> updateWorth(), () -> updateQueued = false);
    }

    private boolean isMenuItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(menuKey, PersistentDataType.BYTE);
    }

    /** Everything players put in, emptied out of the menu. */
    private List<ItemStack> takeAll() {
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < ITEM_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && !stack.getType().isAir() && !isMenuItem(stack)) {
                items.add(stack);
            }
            inventory.setItem(slot, null);
        }
        return items;
    }

    /** Puts items in the player's inventory, dropping what doesn't fit at their feet. */
    static void giveBack(Player player, Collection<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        Location at = player.getLocation();
        for (ItemStack left : player.getInventory().addItem(items.toArray(ItemStack[]::new)).values()) {
            player.getWorld().dropItemNaturally(at, left);
        }
    }

    /** Gives everything back to whoever has a sell menu open (when the plugin stops). */
    static void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof SellMenu menu) {
                    menu.cancelled = true;
                    giveBack(player, menu.takeAll());
                    player.closeInventory();
                }
            } catch (RuntimeException ignored) {
                // Folia may not let us touch players from here while stopping; their items stay in the menu.
            }
        }
    }

    /** Handles clicks, drags and closing for every open sell menu. */
    static final class Events implements Listener {

        private final DiscordLinkedFAD plugin;

        Events(DiscordLinkedFAD plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder(false) instanceof SellMenu menu)
                    || !(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            int raw = event.getRawSlot();
            if (raw >= ITEM_SLOTS && raw < SIZE) {
                event.setCancelled(true);
                if (raw == CANCEL_SLOT) {
                    menu.cancelled = true;
                    player.getScheduler().run(plugin, task -> player.closeInventory(), null);
                } else if (raw == SELL_SLOT) {
                    menu.sellNow = true;
                    player.getScheduler().run(plugin, task -> player.closeInventory(), null);
                }
                return;
            }
            if (menu.isMenuItem(event.getCurrentItem()) || menu.isMenuItem(event.getCursor())) {
                event.setCancelled(true);
                return;
            }
            menu.queueUpdate(player);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDrag(InventoryDragEvent event) {
            if (!(event.getView().getTopInventory().getHolder(false) instanceof SellMenu menu)
                    || !(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            for (int raw : event.getRawSlots()) {
                if (raw >= ITEM_SLOTS && raw < SIZE) {
                    event.setCancelled(true);
                    return;
                }
            }
            menu.queueUpdate(player);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            if (!(event.getInventory().getHolder(false) instanceof SellMenu menu)
                    || !(event.getPlayer() instanceof Player player)) {
                return;
            }
            List<ItemStack> items = menu.takeAll();
            if (items.isEmpty()) {
                return;
            }
            // Only the player closing it (or the Sell button) sells. Leaving the game, dying, teleporting or another
            // plugin closing it gives everything back instead, so nothing is ever sold by surprise.
            boolean sell = !menu.cancelled && (menu.sellNow || event.getReason() == InventoryCloseEvent.Reason.PLAYER);
            if (!sell) {
                giveBack(player, items);
                if (menu.cancelled) {
                    player.sendMessage(plugin.settings().message("sell-cancelled"));
                }
                return;
            }
            plugin.sell(player, items);
        }
    }
}
