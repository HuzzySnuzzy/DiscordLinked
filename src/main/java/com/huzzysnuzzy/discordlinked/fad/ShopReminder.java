package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.Cosmetic;
import com.huzzysnuzzy.discordlinked.storage.CreditAccount;
import com.huzzysnuzzy.discordlinked.storage.OwnedCosmetic;
import com.huzzysnuzzy.discordlinked.storage.ShopOverride;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Reminds players in game that they have credits to spend in the website's Shop: at most once every
 * shop-reminder-minutes per player, counted from when they joined. Once one is due it comes with the next credits
 * they earn (for playing or for an advancement), so it reads as "and here's where to spend them"; on a server
 * where they don't earn (or while they're AFK, or with no credits for playing) it comes on its own. Only linked
 * players who can afford something in the Shop or the Cosmetics shop are reminded, and only while the website is
 * up and one of the shops is open. Each player can turn it off (/dla reminders off, or in the Shop).
 */
public final class ShopReminder {

    /** How often to look for a reminder that's due. */
    private static final long TICK_SECONDS = 30;
    /** After credits for playing, so the reminder comes just after the "+5 credits" line. */
    private static final long AFTER_REWARD_MILLIS = 1500;

    private final DiscordLinked plugin;
    private final Community community;
    /** When each online player's next reminder is due. Set when first seen on a server, cleared when they leave. */
    private final Map<UUID, Instant> due = new ConcurrentHashMap<>();
    /** Players a reminder is being looked into for right now, so a reward and the timer don't both send one. */
    private final Set<UUID> sending = ConcurrentHashMap.newKeySet();

    ShopReminder(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
    }

    void start() {
        plugin.proxy().getScheduler().buildTask(plugin, this::tick)
                .delay(TICK_SECONDS, TimeUnit.SECONDS).repeat(TICK_SECONDS, TimeUnit.SECONDS).schedule();
    }

    private Duration every() {
        return Duration.ofMinutes(plugin.config().shopReminderMinutes());
    }

    void left(UUID uuid) {
        due.remove(uuid);
    }

    /** The player just earned credits in game: if a reminder is due, it comes now. */
    void earned(Player player) {
        if (plugin.config().shopReminderMinutes() <= 0) {
            return;
        }
        Instant at = due.get(player.getUniqueId());
        if (at != null && !Instant.now().isBefore(at)) {
            plugin.proxy().getScheduler().buildTask(plugin, () -> remind(player))
                    .delay(AFTER_REWARD_MILLIS, TimeUnit.MILLISECONDS).schedule();
        }
    }

    /**
     * Every 30 seconds: starts the clock for players who just got on a server, and reminds those whose reminder is
     * due and who aren't going to earn credits for playing soon (if they are, it waits for that, at most one block
     * of play longer).
     */
    private void tick() {
        PluginConfig c = plugin.config();
        if (c.shopReminderMinutes() <= 0 || !c.fadEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (Player player : plugin.proxy().getAllPlayers()) {
            Optional<String> server = player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
            if (server.isEmpty()) {
                continue;
            }
            Instant at = due.computeIfAbsent(player.getUniqueId(), uuid -> now.plus(every()));
            boolean earnsSoon = c.creditsPlaytimeAmount() > 0 && !community.isAfk(player.getUniqueId())
                    && plugin.servers().creditsEnabled(server.get());
            Duration wait = earnsSoon ? Duration.ofMinutes(c.creditsPlaytimeMinutes()).plusMinutes(1) : Duration.ZERO;
            if (!now.isBefore(at.plus(wait))) {
                remind(player);
            }
        }
    }

    /** Sends the reminder if the player can use it, and starts the clock for the next one either way. */
    private void remind(Player player) {
        UUID uuid = player.getUniqueId();
        if (!sending.add(uuid)) {
            return;
        }
        try {
            Instant at = due.get(uuid);
            Instant now = Instant.now();
            if (at == null || now.isBefore(at) || plugin.proxy().getPlayer(uuid).filter(p -> p == player).isEmpty()) {
                return; // already reminded, or left
            }
            due.put(uuid, now.plus(every()));
            PluginConfig c = plugin.config();
            if (c.shopReminderMinutes() <= 0 || !c.fadEnabled() || plugin.web() == null
                    || plugin.messages().isEmpty("minecraft.fad.shop-reminder")
                    || plugin.database().findByUuid(uuid).isEmpty() || !plugin.database().shopReminders(uuid)) {
                return;
            }
            long credits = plugin.database().creditAccount(uuid).map(CreditAccount::credits).orElse(0L);
            long spendable = spendable(uuid, credits);
            long cheapest = cheapest(uuid);
            if (cheapest <= 0 || spendable < cheapest) {
                return;
            }
            player.sendMessage(plugin.messages().minecraft("minecraft.fad.shop-reminder", TagResolver.resolver(
                    Placeholder.component("link", plugin.messages().minecraft("minecraft.fad.shop-reminder-link")
                            .clickEvent(ClickEvent.runCommand("/dla shop"))
                            .hoverEvent(HoverEvent.showText(plugin.messages().minecraft("minecraft.fad.shop-reminder-hover")))),
                    Placeholder.component("off", plugin.messages().minecraft("minecraft.fad.shop-reminder-off")
                            .clickEvent(ClickEvent.runCommand("/dla reminders off"))
                            .hoverEvent(HoverEvent.showText(plugin.messages().minecraft("minecraft.fad.shop-reminder-off-hover"))))),
                    "credits", c.credits(spendable)));
        } catch (SQLException e) {
            plugin.logger().warn("Could not look into a Shop reminder for {}", player.getUsername(), e);
        } finally {
            sending.remove(uuid);
        }
    }

    /** Credits, plus what their microcredits are worth when they pay the rest with them. */
    private long spendable(UUID uuid, long credits) throws SQLException {
        long rate = community.market().shopRate(uuid);
        if (rate <= 0) {
            return credits;
        }
        return credits + plugin.database().creditAccount(uuid).map(CreditAccount::micro).orElse(0L) / rate;
    }

    /**
     * What the cheapest thing the player could buy costs: one of the cheapest Shop item (in its chest), or a cosmetic
     * on sale that they don't have yet. 0 when there's nothing.
     */
    private long cheapest(UUID uuid) throws SQLException {
        long best = 0;
        Shop shop = community.shop();
        if (shop.open()) {
            Map<String, ShopOverride> overrides = plugin.database().shopOverrides();
            Set<String> known = shop.availableItems();
            long cents = Long.MAX_VALUE;
            for (Shop.Item item : shop.catalog().values()) {
                ShopOverride override = overrides.get(item.id());
                if (item.id().equals(Shop.CHEST_ID) || override != null && override.disabled()
                        || known != null && !known.contains(item.id())) {
                    continue;
                }
                cents = Math.min(cents, shop.unitCents(item, override));
            }
            if (cents != Long.MAX_VALUE) {
                best = Shop.total(cents + shop.chestCents(overrides));
            }
        }
        Cosmetics cosmetics = community.cosmetics();
        if (cosmetics.open()) {
            Set<Long> owned = cosmetics.owned(uuid).stream().map(OwnedCosmetic::cosmeticId).collect(Collectors.toSet());
            for (Cosmetic cosmetic : cosmetics.catalog()) {
                if (!cosmetic.forSale() || cosmetic.price() <= 0 || owned.contains(cosmetic.id())
                        || cosmetic.kind().equals(Cosmetics.CARD) && cosmetic.value().isEmpty()) {
                    continue;
                }
                best = best == 0 ? cosmetic.price() : Math.min(best, cosmetic.price());
            }
        }
        return best;
    }
}
