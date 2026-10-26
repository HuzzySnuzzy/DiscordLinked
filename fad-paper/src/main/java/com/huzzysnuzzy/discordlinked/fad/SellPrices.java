package com.huzzysnuzzy.discordlinked.fad;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What items sell for, as last sent by the proxy (it sends a fresh list whenever prices change). Only used to show
 * prices and the /sell menu's running total; the proxy works out what a sale actually pays.
 *
 * <p>Prices are in thousandths of a microcredit, so cheap items like dirt still add up.
 */
final class SellPrices {

    /** One item's price right now and normally (they differ when prices drop after a lot was sold). */
    record Price(long now, long normal) {
    }

    private volatile Map<String, Price> prices;
    private volatile boolean open;
    private volatile String symbol = "µc";
    /** The list being received (it comes in a few parts). */
    private Map<String, Price> incoming;

    /** Whether the proxy has sent prices and selling is open. */
    boolean available() {
        return open && prices != null;
    }

    /** Whether the proxy has said anything about selling (so it supports it). */
    boolean known() {
        return prices != null;
    }

    String symbol() {
        return symbol;
    }

    synchronized void receive(int part, int parts, boolean open, String symbol, Map<String, Price> chunk) {
        if (part == 0) {
            incoming = new HashMap<>();
        }
        if (incoming == null) {
            return; // missed the first part; the next full list fixes it
        }
        incoming.putAll(chunk);
        if (part == parts - 1) {
            this.prices = Map.copyOf(incoming);
            this.open = open;
            this.symbol = symbol;
            incoming = null;
        }
    }

    /** The item's price, or null if it can't be sold at all. */
    Price price(Material material) {
        Map<String, Price> current = prices;
        return current == null ? null : current.get(material.getKey().getKey());
    }

    /**
     * Whether this stack can be sold: a plain item with a price. Anything with its own data (enchantments, a name,
     * damage, contents, potion effects...) can't be, because the price is for the plain item.
     */
    boolean sellable(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && price(stack.getType()) != null && isPlain(stack);
    }

    static boolean isPlain(ItemStack stack) {
        return stack.isSimilar(new ItemStack(stack.getType()));
    }

    /** What a stack is worth right now, in thousandths of a microcredit; 0 if it can't be sold. */
    long worth(ItemStack stack) {
        if (!sellable(stack)) {
            return 0;
        }
        return price(stack.getType()).now() * stack.getAmount();
    }

    /** "1,234 µc", or "0.3 µc" for small fractions. */
    String format(long milli) {
        if (milli % 1000 == 0 || milli >= 100_000) {
            return String.format(Locale.ROOT, "%,d", milli / 1000) + " " + symbol;
        }
        String number = milli >= 10_000 ? String.format(Locale.ROOT, "%.1f", milli / 1000.0)
                : String.format(Locale.ROOT, "%.2f", milli / 1000.0).replaceAll("0$", "");
        return number + " " + symbol;
    }

    /** Whole microcredits, rounded down like the proxy pays them. */
    String formatTotal(long milli) {
        return format(milli / 1000 * 1000);
    }
}
