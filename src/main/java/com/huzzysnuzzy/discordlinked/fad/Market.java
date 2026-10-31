package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.CreditAccount;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.huzzysnuzzy.discordlinked.storage.MicroBoardRow;
import com.huzzysnuzzy.discordlinked.storage.ShopOverride;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Selling items in game for microcredits, a second currency with its own leaderboard. What an item sells for comes
 * from its Shop price: sell-price-percent of it (never more than the Shop asks, so buying and selling back can't
 * pay), times the item's own sell percentage. Microcredits can be exchanged for credits, never back.
 *
 * <p>Prices are kept in thousandths of a microcredit so cheap items still add up. With sell-price-drop on, every
 * item remembers how much of it was sold lately ("pressure", in thousandths of a credit's worth, halving every
 * sell-price-recovery-hours); its price is normal × drop / (drop + pressure), so it's half once sell-price-drop-after
 * credits' worth has been sold. A sale pays the average price over the amount sold, so splitting a sale up
 * doesn't pay more.
 *
 * <p>The Minecraft servers get the current prices (for /worth and the /sell menu's running total) and send what
 * was sold; the amount paid is always worked out here.
 */
public final class Market {

    /** Most of one item in one sale: a double chest of stacks, a few times over. */
    static final int MAX_AMOUNT = 100_000;
    private static final int TOP_SIZE = 10;
    private static final long PRICE_PUSH_SECONDS = 30;
    private static final String WEEKLY_KEY = "weekly-posted";

    /** One item and how many of it. */
    public record Line(String id, int amount) {
    }

    /** One item's sell price: normal and right now (after dropping), in thousandths of a microcredit each. */
    public record Price(String id, long normal, long now) {
    }

    private final DiscordLinked plugin;
    private final Community community;
    /** Per item, by id; only items that were sold while dropping prices were on. */
    private final Map<String, Database.MarketPressure> pressure = new ConcurrentHashMap<>();
    /** The price list last sent to each server, so unchanged lists aren't sent again. */
    private final Map<String, Integer> sentPrices = new ConcurrentHashMap<>();

    Market(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
    }

    void start() {
        try {
            pressure.putAll(plugin.database().marketPressure());
        } catch (SQLException e) {
            plugin.logger().warn("Could not load the market's sell history", e);
        }
        plugin.proxy().getScheduler().buildTask(plugin, this::pushPrices)
                .delay(PRICE_PUSH_SECONDS, TimeUnit.SECONDS).repeat(PRICE_PUSH_SECONDS, TimeUnit.SECONDS).schedule();
        plugin.proxy().getScheduler().buildTask(plugin, this::weeklyPost)
                .delay(2, TimeUnit.MINUTES).repeat(10, TimeUnit.MINUTES).schedule();
    }

    private PluginConfig config() {
        return plugin.config();
    }

    public boolean open() {
        return config().marketOpen();
    }

    /** Whether players can sell on this server: the market is open and earning microcredits is on there. */
    public boolean open(String server) {
        return open() && plugin.servers().microEnabled(server);
    }

    // ---- Prices ----

    /** What one of {@code item} sells for normally, in thousandths of a microcredit; 0 if it can't be sold. */
    public long normalPrice(Shop.Item item, ShopOverride override) {
        if (override != null && override.sellDisabled()) {
            return 0;
        }
        PluginConfig c = config();
        // A percentage of the Shop price, never above it.
        double percent = Math.min(100.0, c.sellPricePercent() * (override == null ? 100 : override.sellPercent()) / 100.0);
        long shopCents = community.shop().unitCents(item, override);
        return Math.max(0, Math.round(shopCents * (double) c.microPerCredit() * percent / 10.0));
    }

    /** How much of an item was sold lately, decayed to now, in thousandths of a credit's worth. */
    private double pressureNow(String id, long now) {
        Database.MarketPressure p = pressure.get(id);
        if (p == null) {
            return 0;
        }
        double halfLifeMs = config().sellPriceRecoveryHours() * 3_600_000.0;
        return p.pressure() * Math.pow(0.5, Math.max(0, now - p.updatedAt()) / halfLifeMs);
    }

    /** The "half price after" amount, in the same unit as pressure. */
    private double dropAfter() {
        return config().sellPriceDropAfter() * 1000.0;
    }

    /** What the price is right now, as a fraction of normal (1 when prices don't drop). */
    private double factorNow(String id, long now) {
        if (!config().sellPriceDrop()) {
            return 1;
        }
        double drop = dropAfter();
        return drop / (drop + pressureNow(id, now));
    }

    /** Every item that can be sold, with its prices, in Shop order. Items a server doesn't know are left out. */
    public List<Price> prices(Set<String> onlyThese) throws SQLException {
        Map<String, ShopOverride> overrides = plugin.database().shopOverrides();
        long now = System.currentTimeMillis();
        List<Price> list = new ArrayList<>();
        for (Shop.Item item : community.shop().catalog().values()) {
            if (onlyThese != null && !onlyThese.contains(item.id())) {
                continue;
            }
            long normal = normalPrice(item, overrides.get(item.id()));
            if (normal > 0) {
                list.add(new Price(item.id(), normal, Math.round(normal * factorNow(item.id(), now))));
            }
        }
        return list;
    }

    // ---- Selling ----

    /** What happened to a sale: microcredits paid and the lines that were refused (to give back). */
    public record Sale(long paid, long balance, List<Line> refused) {
    }

    /**
     * Pays a player for items their server already took from them. Lines that can't be sold are refused, so the
     * server gives them back. Called off the network threads.
     */
    Sale sell(Player player, String server, List<Line> lines) {
        PluginConfig c = config();
        if (!open(server)) {
            // Switched off on this server only, or everywhere.
            player.sendMessage(plugin.messages().minecraft(open() ? "minecraft.market.closed-here"
                    : "minecraft.market.closed"));
            return new Sale(0, -1, lines);
        }
        Map<String, ShopOverride> overrides;
        try {
            overrides = plugin.database().shopOverrides();
        } catch (SQLException e) {
            plugin.logger().error("Could not sell for {}", player.getUsername(), e);
            player.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
            return new Sale(0, -1, lines);
        }
        Set<String> known = community.shop().serverItems().get(server);
        Map<String, Integer> totals = new LinkedHashMap<>();
        List<Line> refused = new ArrayList<>();
        for (Line line : lines) {
            Shop.Item item = community.shop().catalog().get(line.id());
            boolean ok = item != null && line.amount() > 0 && line.amount() <= MAX_AMOUNT
                    && (known == null || known.contains(line.id())) && normalPrice(item, overrides.get(line.id())) > 0;
            if (ok) {
                totals.merge(line.id(), line.amount(), Integer::sum);
            } else {
                refused.add(line);
            }
        }
        if (totals.isEmpty()) {
            player.sendMessage(plugin.messages().minecraft("minecraft.market.nothing"));
            return new Sale(0, -1, refused);
        }
        long now = System.currentTimeMillis();
        boolean dropping = c.sellPriceDrop();
        double milli = 0;
        Map<String, Database.MarketPressure> changed = new HashMap<>();
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            Shop.Item item = community.shop().catalog().get(entry.getKey());
            long normal = normalPrice(item, overrides.get(entry.getKey()));
            double worth = normal * (double) entry.getValue();
            if (!dropping) {
                milli += worth;
                continue;
            }
            // The average of drop / (drop + p) while p goes from its current value up by what this sale adds.
            double drop = dropAfter();
            double before = pressureNow(entry.getKey(), now);
            double added = worth / c.microPerCredit();
            double factor = added <= 0 ? 1 : drop / added * Math.log((drop + before + added) / (drop + before));
            milli += worth * factor;
            Database.MarketPressure after = new Database.MarketPressure(before + added, now);
            pressure.put(entry.getKey(), after);
            changed.put(entry.getKey(), after);
        }
        long paid = (long) Math.floor(milli / 1000.0);
        String summary = summary(totals);
        long balance;
        try {
            if (!changed.isEmpty()) {
                plugin.database().setMarketPressure(changed);
            }
            balance = paid > 0
                    ? plugin.database().addMicro(player.getUniqueId(), player.getUsername(), paid, "SALE",
                    summary + " on " + server, null, true)
                    : plugin.database().creditAccount(player.getUniqueId()).map(CreditAccount::micro).orElse(0L);
        } catch (SQLException e) {
            plugin.logger().error("Could not pay {} for a sale", player.getUsername(), e);
            player.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
            return new Sale(0, -1, lines);
        }
        plugin.audit().log(player.getUniqueId(), player.getUsername(), community.linkedDiscordId(player.getUniqueId()),
                EventTypes.SALE, "sold " + summary + " on " + server + " for " + c.micro(paid) + ", now "
                        + c.micro(balance), null, server);
        player.sendMessage(plugin.messages().minecraft("minecraft.market.sold", "items", shortSummary(totals),
                "amount", c.micro(paid), "balance", c.micro(balance)));
        return new Sale(paid, balance, refused);
    }

    // ---- Exchanging ----

    /** What an exchange did, with a message for the player. {@code ok} false = nothing changed. */
    public record ExchangeResult(boolean ok, String message, long micro, long credits) {
    }

    /** Turns microcredits into {@code credits} credits ({@code -1} = as many as they can). */
    public ExchangeResult exchange(UUID uuid, String name, long credits, String actor) throws SQLException {
        PluginConfig c = config();
        if (!open()) {
            return new ExchangeResult(false, "The market is closed right now.", -1, -1);
        }
        long rate = c.microPerCredit();
        long has = plugin.database().creditAccount(uuid).map(CreditAccount::micro).orElse(0L);
        if (credits == -1) {
            credits = has / rate;
        }
        if (credits > Long.MAX_VALUE / rate) {
            credits = Long.MAX_VALUE / rate;
        }
        if (credits <= 0) {
            return new ExchangeResult(false, has < rate
                    ? "You need at least " + c.micro(rate) + " for 1 " + c.creditsName() + "; you have " + c.micro(has) + "."
                    : "Exchange at least 1 " + c.creditsName() + ".", -1, -1);
        }
        long cost = credits * rate;
        Database.Exchange done = plugin.database().exchangeMicro(uuid, name, cost, credits,
                c.micro(cost) + " → " + c.credits(credits), actor);
        if (done == null) {
            return new ExchangeResult(false, c.credits(credits) + " costs " + c.micro(cost) + " but you have "
                    + c.micro(has) + ".", -1, -1);
        }
        plugin.audit().log(uuid, name, community.linkedDiscordId(uuid), EventTypes.EXCHANGE,
                "exchanged " + c.micro(cost) + " for " + c.credits(credits) + ", now " + c.micro(done.micro()) + " and "
                        + c.credits(done.credits()), actor);
        return new ExchangeResult(true, "Exchanged " + c.micro(cost) + " for " + c.credits(credits) + ".",
                done.micro(), done.credits());
    }

    /** /exchange in game: an amount of credits, or "all". */
    void exchangeFromGame(Player player, String arg) {
        PluginConfig c = config();
        long credits;
        if (arg.equalsIgnoreCase("all")) {
            credits = -1;
        } else {
            try {
                credits = Long.parseLong(arg.replace(",", ""));
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.messages().minecraft("minecraft.market.exchange-usage",
                        "rate", c.micro(c.microPerCredit()), "currency", c.creditsName()));
                return;
            }
        }
        try {
            ExchangeResult result = exchange(player.getUniqueId(), player.getUsername(), credits, null);
            player.sendMessage(result.ok()
                    ? plugin.messages().minecraft("minecraft.market.exchanged", "message", result.message(),
                    "micro", c.micro(result.micro()), "credits", c.credits(result.credits()))
                    : plugin.messages().minecraft("minecraft.market.exchange-failed", "message", result.message()));
        } catch (SQLException e) {
            plugin.logger().error("Database error in /exchange", e);
            player.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
        }
    }

    // ---- Leaderboard ----

    /** Whether the leaderboard ranks by everything ever sold rather than the balance. */
    public boolean byEarned() {
        return "earned".equals(config().microLeaderboard());
    }

    /** The best places: players on their own and groups as one. */
    public List<MicroBoardRow> top(int limit) throws SQLException {
        List<MicroBoardRow> board = plugin.database().microBoard(byEarned());
        return board.subList(0, Math.min(limit, board.size()));
    }

    /** A player's place (their group's, if they're in one), or 0 if they're not on the board. */
    public long rank(UUID uuid) throws SQLException {
        List<MicroBoardRow> board = plugin.database().microBoard(byEarned());
        for (int i = 0; i < board.size(); i++) {
            MicroBoardRow row = board.get(i);
            if (row.group() == null ? row.uuid().equals(uuid)
                    : row.group().members().stream().anyMatch(m -> m.uuid().equals(uuid))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** How a place is named in chat: the player, or the group with how many are in it. */
    static String placeName(MicroBoardRow row) {
        return row.group() == null ? row.name() : row.name() + " (" + row.group().members().size() + " players)";
    }

    /** /mct in game: the top 10 and where the player stands. */
    void topFromGame(Player player) {
        PluginConfig c = config();
        try {
            List<MicroBoardRow> top = top(TOP_SIZE);
            if (top.isEmpty()) {
                player.sendMessage(plugin.messages().minecraft("minecraft.market.top-empty", "currency", c.microNamePlural()));
                return;
            }
            player.sendMessage(plugin.messages().minecraft("minecraft.market.top-header", "currency", c.microNamePlural(),
                    "ranking", byEarned() ? "earned in total" : "held"));
            for (int i = 0; i < top.size(); i++) {
                MicroBoardRow row = top.get(i);
                player.sendMessage(plugin.messages().minecraft(i < 3 ? "minecraft.market.top-line-" + (i + 1)
                                : "minecraft.market.top-line", "rank", String.valueOf(i + 1), "player", placeName(row),
                        "balance", c.micro(row.value())));
            }
            Optional<CreditAccount> me = plugin.database().creditAccount(player.getUniqueId());
            long rank = rank(player.getUniqueId());
            player.sendMessage(plugin.messages().minecraft("minecraft.market.top-self",
                    "rank", rank == 0 ? "-" : String.valueOf(rank),
                    "balance", c.micro(me.map(CreditAccount::micro).orElse(0L)),
                    "earned", c.micro(me.map(CreditAccount::microEarned).orElse(0L))));
        } catch (SQLException e) {
            plugin.logger().error("Database error in /mct", e);
            player.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
        }
    }

    static String medal(int rank) {
        return switch (rank) {
            case 1 -> "🥇";
            case 2 -> "🥈";
            case 3 -> "🥉";
            default -> "";
        };
    }

    // ---- Keeping the servers' price lists current ----

    /**
     * Sends a server its prices now (after it said hello), through one of its players. A server where selling is
     * off gets none, with open false, so its /sell, /sellhand and /worth say so.
     */
    void sendPrices(ServerConnection connection) {
        String server = connection.getServerInfo().getName();
        boolean open = open(server);
        try {
            List<Price> prices = open ? prices(community.shop().serverItems().get(server)) : List.of();
            community.bridge().prices(connection, open, config().microSymbol(), prices);
            sentPrices.put(server, fingerprint(prices, open));
        } catch (SQLException e) {
            plugin.logger().warn("Could not send sell prices to {}", server, e);
        }
    }

    /** Something that changes the prices or where selling is on changed: servers get the new prices now. */
    public void pricesChanged() {
        plugin.proxy().getScheduler().buildTask(plugin, this::pushPrices).schedule();
    }

    /** Every 30 seconds: servers whose prices changed (settings, the Shop page, dropping prices) get the new ones. */
    private synchronized void pushPrices() {
        Map<String, ServerConnection> carriers = new HashMap<>();
        for (Player player : plugin.proxy().getAllPlayers()) {
            player.getCurrentServer().ifPresent(connection -> carriers.putIfAbsent(connection.getServerInfo().getName(), connection));
        }
        for (Map.Entry<String, ServerConnection> entry : carriers.entrySet()) {
            String server = entry.getKey();
            if (!community.servers().containsKey(server)) {
                continue;
            }
            try {
                boolean open = open(server);
                List<Price> prices = open ? prices(community.shop().serverItems().get(server)) : List.of();
                int now = fingerprint(prices, open);
                Integer last = sentPrices.get(server);
                if (last == null || last != now) {
                    community.bridge().prices(entry.getValue(), open, config().microSymbol(), prices);
                    sentPrices.put(server, now);
                }
            } catch (SQLException e) {
                plugin.logger().warn("Could not update sell prices on {}", server, e);
            }
        }
    }

    /** Changes when any price changes noticeably (dropping prices move a little all the time). */
    private int fingerprint(List<Price> prices, boolean open) {
        int hash = open ? 1 : 0;
        hash = 31 * hash + config().microSymbol().hashCode();
        for (Price price : prices) {
            hash = 31 * hash + price.id().hashCode();
            hash = 31 * hash + Long.hashCode(Math.round(Math.log1p(price.now()) * 100));
        }
        return hash;
    }

    // ---- Weekly top sellers on Discord ----

    private void weeklyPost() {
        PluginConfig c = config();
        if (!open() || !c.marketWeeklyPost() || c.fadChannel() == 0L) {
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate monday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        try {
            Optional<String> posted = plugin.database().marketState(WEEKLY_KEY);
            if (posted.isPresent() && posted.get().equals(monday.toString())) {
                return;
            }
            Instant to = monday.atStartOfDay(zone).toInstant();
            Instant from = monday.minusWeeks(1).atStartOfDay(zone).toInstant();
            List<Database.SalesRow> top = plugin.database().topSellers(from, to, TOP_SIZE);
            plugin.database().setMarketState(WEEKLY_KEY, monday.toString());
            if (top.isEmpty() || plugin.messages().isEmpty("discord.market.weekly")) {
                return;
            }
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < top.size(); i++) {
                Database.SalesRow row = top.get(i);
                String medal = medal(i + 1);
                lines.append(plugin.messages().discord("discord.market.weekly-line", "rank", String.valueOf(i + 1),
                        "medal", medal.isEmpty() ? "**" + (i + 1) + ".**" : medal,
                        "player", MarkdownSanitizer.escape(row.name()), "amount", MarkdownSanitizer.escape(c.micro(row.amount())),
                        "sales", String.valueOf(row.sales()))).append('\n');
            }
            String text = plugin.messages().discord("discord.market.weekly", "lines", lines.toString().strip(),
                    "currency", MarkdownSanitizer.escape(c.microNamePlural()));
            plugin.bot().post(c.fadChannel(), text.length() > 2000 ? text.substring(0, 1999) + "…" : text, null);
        } catch (SQLException e) {
            plugin.logger().warn("Could not post the weekly top sellers", e);
        }
    }

    // ---- Helpers ----

    private static String summary(Map<String, Integer> totals) {
        List<String> parts = new ArrayList<>();
        totals.forEach((id, amount) -> parts.add(amount + " × " + Shop.name(id)));
        return String.join(", ", parts);
    }

    /** At most a few items, then "and 3 more". */
    private static String shortSummary(Map<String, Integer> totals) {
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            if (shown++ == 4) {
                parts.add("and " + (totals.size() - 4) + " more");
                break;
            }
            parts.add(String.format(java.util.Locale.ROOT, "%,d", entry.getValue()) + " × " + Shop.name(entry.getKey()));
        }
        return String.join(", ", parts);
    }
}
