package com.huzzysnuzzy.discordlinked.servers;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.discord.DiscordBot;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Maintenance mode: a server in maintenance (Settings -> Backend servers, or /dlink maintenance) only lets admins in.
 * Who counts as one is decided in {@link #check}. Everyone else is turned away with the server's message: in chat
 * when they're on another server, or sent to the next server Velocity would try (its forced hosts for the address
 * they joined with, else its try order) when it's the server they join on or fall back to after a kick, and
 * disconnected with the message when there's none. Turning maintenance on moves the players already there, and so
 * does arriving on it just after (a connection that was already under way).
 *
 * <p>The listeners run last ({@link Short#MIN_VALUE}), so they see the server other plugins picked. Deciding needs the
 * database, and Discord when the admin role isn't cached, so it runs in an {@link EventTask#async} task, and only for
 * servers in maintenance.
 */
public final class Maintenance {

    /** Lets a player join servers in maintenance, without the admin role. */
    public static final String BYPASS_PERMISSION = "discordlinked.maintenance.bypass";
    /**
     * How long to wait for Discord when a player's admin role isn't cached yet: for the bot to connect (right after
     * the proxy starts) and then for its answer.
     */
    private static final long DISCORD_WAIT_MILLIS = 4000;
    /** How long moving one player off a server may take before they're disconnected instead. */
    private static final long MOVE_WAIT_SECONDS = 15;

    /** Whether a player may join servers in maintenance; UNKNOWN when Discord couldn't say in time. */
    public enum Verdict { ALLOWED, DENIED, UNKNOWN }

    private final DiscordLinked plugin;
    /**
     * Per player, the servers already tried since they were last on one (lower case): Velocity keeps offering the
     * first server of its try order after a failed connection, so without this a kicked player could be sent back and
     * forth between two servers that are down for ever.
     */
    private final Map<UUID, Set<String>> tried = new ConcurrentHashMap<>();

    public Maintenance(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    // ---- Who may join ----

    /**
     * Whether the player may join servers in maintenance: they have {@link #BYPASS_PERMISSION}, or their linked
     * Discord account is the website's settings owner (web-owner-id, else the Discord server's owner) or has the
     * admin role. Blocks on the database and on Discord (up to {@link #DISCORD_WAIT_MILLIS} in all, also waiting for
     * the bot to connect), so never call it on an event thread.
     */
    public Verdict check(Player player) {
        return check(player, System.currentTimeMillis() + DISCORD_WAIT_MILLIS);
    }

    /**
     * {@link #check(Player)}, waiting for Discord until {@code deadline} at most (System.currentTimeMillis()). With
     * a deadline already past, Discord isn't waited for or asked: UNKNOWN when it would have to be.
     */
    private Verdict check(Player player, long deadline) {
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return Verdict.ALLOWED;
        }
        Optional<LinkedAccount> account;
        try {
            account = plugin.database().findByUuid(player.getUniqueId());
        } catch (SQLException e) {
            plugin.logger().warn("Could not check whether {} may join a server in maintenance", player.getUsername(), e);
            return Verdict.UNKNOWN;
        }
        if (account.isEmpty()) {
            return Verdict.DENIED;
        }
        long discordId = account.get().discordId();
        long configuredOwner = plugin.config().webOwnerId();
        if (configuredOwner != 0L && configuredOwner == discordId) {
            return Verdict.ALLOWED;
        }
        DiscordBot bot = plugin.bot();
        DiscordBot.MemberInfo member = bot.memberInfo(discordId);
        if (member != null && member.admin()) {
            return Verdict.ALLOWED;
        }
        // Without web-owner-id, the owner is the Discord server's, which is only known once the bot is connected.
        if (member == null || configuredOwner == 0L) {
            while (!bot.isReady() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Verdict.UNKNOWN;
                }
            }
            if (!bot.isReady() || (member == null && System.currentTimeMillis() >= deadline)) {
                return Verdict.UNKNOWN;
            }
            if (configuredOwner == 0L && bot.guildOwnerId() == discordId) {
                return Verdict.ALLOWED;
            }
        }
        if (member == null) {
            long wait = deadline - System.currentTimeMillis();
            try {
                member = CompletableFuture.supplyAsync(() -> bot.checkMember(discordId)).get(wait, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                plugin.logger().warn("Discord didn't say in time whether {} has the admin role", player.getUsername());
                return Verdict.UNKNOWN;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Verdict.UNKNOWN;
            }
        }
        return member.admin() ? Verdict.ALLOWED : Verdict.DENIED;
    }

    /** For joining: only a player known to be allowed gets in (one Discord couldn't vouch for doesn't). */
    public boolean mayJoin(Player player) {
        return check(player) == Verdict.ALLOWED;
    }

    // ---- Turning players away ----

    /** Someone connecting to a server in maintenance who may not: told in chat, or sent elsewhere if it's their first. */
    @Subscribe(priority = Short.MIN_VALUE)
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null || !inMaintenance(target)) {
            return null;
        }
        return EventTask.async(() -> {
            Player player = event.getPlayer();
            if (mayJoin(player)) {
                return;
            }
            String name = target.getServerInfo().getName();
            if (event.getPreviousServer() != null) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                player.sendMessage(message(name));
                return;
            }
            // Their first server (another plugin sent them here, or it went into maintenance a moment ago). A refused
            // first connection would leave them waiting on the proxy with no server, so they go elsewhere or leave.
            Optional<RegisteredServer> next = nextServer(player, Set.of(key(name)));
            if (next.isPresent()) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(next.get()));
            } else {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                player.disconnect(message(name));
            }
        });
    }

    /**
     * Joining the network on a server in maintenance: they start on the next server instead. With none, the server
     * stays chosen and {@link #onServerPreConnect} disconnects them with its message (Velocity's own message for no
     * server would only say there's none).
     */
    @Subscribe(priority = Short.MIN_VALUE)
    public EventTask onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        RegisteredServer chosen = event.getInitialServer().orElse(null);
        if (chosen == null || !inMaintenance(chosen)) {
            return null;
        }
        return EventTask.async(() -> {
            Player player = event.getPlayer();
            if (!mayJoin(player)) {
                nextServer(player, Set.of(key(chosen.getServerInfo().getName()))).ifPresent(event::setInitialServer);
            }
        });
    }

    /**
     * Kicked from a server (or a connection failed) and about to be sent to one in maintenance: the next server
     * instead, leaving out every server tried since they were last on one; with none left, disconnected with the
     * maintenance message (and the kick's reason).
     */
    @Subscribe(priority = Short.MIN_VALUE)
    public EventTask onKicked(KickedFromServerEvent event) {
        if (!(event.getResult() instanceof KickedFromServerEvent.RedirectPlayer redirect)
                || !inMaintenance(redirect.getServer())) {
            return null;
        }
        return EventTask.async(() -> {
            Player player = event.getPlayer();
            if (mayJoin(player)) {
                return;
            }
            String name = redirect.getServer().getServerInfo().getName();
            Set<String> skip = tried.computeIfAbsent(player.getUniqueId(), uuid -> ConcurrentHashMap.newKeySet());
            skip.add(key(event.getServer().getServerInfo().getName()));
            skip.add(key(name));
            Optional<RegisteredServer> next = nextServer(player, skip);
            Component note = redirect.getMessageComponent();
            if (next.isPresent()) {
                skip.add(key(next.get().getServerInfo().getName()));
                event.setResult(note == null ? KickedFromServerEvent.RedirectPlayer.create(next.get())
                        : KickedFromServerEvent.RedirectPlayer.create(next.get(), note));
            } else {
                Component message = message(name);
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(event.getServerKickReason()
                        .map(reason -> reason.append(Component.newline()).append(Component.newline()).append(message))
                        .orElse(message)));
            }
        });
    }

    /**
     * On a server: the fallback chain is over. A connection that was under way when the server went into maintenance
     * lands here too, so it's checked again: someone who may not be there is moved off (like {@link #moveOff}).
     */
    @Subscribe
    public EventTask onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        tried.remove(player.getUniqueId());
        String server = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(null);
        if (server == null || !plugin.servers().inMaintenance(server)) {
            return null;
        }
        return EventTask.async(() -> {
            if (stillOn(player, server) && check(player) == Verdict.DENIED) {
                moveOff(player, server, new HashSet<>(Set.of(key(server))));
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        tried.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Maintenance was just turned on: everyone on the server who may not stay goes to the next server (and is told
     * why), or is disconnected with the message when there's none or the move fails. Players are checked one after
     * another (Discord is only asked about the ones not cached), and one whose admin role Discord couldn't confirm in
     * time stays: only a known non-admin is moved. Returns at once.
     */
    public void moveOff(String server) {
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            // First everyone decided without Discord (cached roles, unlinked players), then the rest, one at a time.
            List<Player> unsure = new ArrayList<>();
            for (Player player : plugin.proxy().getAllPlayers()) {
                if (stillOn(player, server)) {
                    Verdict verdict = check(player, 0);
                    if (verdict == Verdict.DENIED) {
                        moveOff(player, server, new HashSet<>(Set.of(key(server))));
                    } else if (verdict == Verdict.UNKNOWN) {
                        unsure.add(player);
                    }
                }
            }
            // Waiting for the bot to connect (right after a proxy start) is shared; each Discord answer has its own time.
            long botDeadline = System.currentTimeMillis() + DISCORD_WAIT_MILLIS;
            for (Player player : unsure) {
                if (!plugin.servers().inMaintenance(server)) {
                    return; // turned off again meanwhile
                }
                long deadline = plugin.bot().isReady() ? System.currentTimeMillis() + DISCORD_WAIT_MILLIS : botDeadline;
                if (stillOn(player, server) && check(player, deadline) == Verdict.DENIED) {
                    moveOff(player, server, new HashSet<>(Set.of(key(server))));
                }
            }
        }).schedule();
    }

    /** Sends the player to the next server not in {@code skip}, trying the one after that if it can't be reached. */
    private void moveOff(Player player, String server, Set<String> skip) {
        if (!stillOn(player, server) || !plugin.servers().inMaintenance(server)) {
            return;
        }
        Optional<RegisteredServer> next = nextServer(player, skip);
        if (next.isEmpty()) {
            player.disconnect(message(server));
            return;
        }
        String to = next.get().getServerInfo().getName();
        player.createConnectionRequest(next.get()).connect().orTimeout(MOVE_WAIT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((result, error) -> {
                    ConnectionRequestBuilder.Status status = error == null ? result.getStatus() : null;
                    if (status == ConnectionRequestBuilder.Status.SUCCESS) {
                        player.sendMessage(message(server));
                        player.sendMessage(plugin.messages().minecraft("minecraft.maintenance.moved", "server", server,
                                "to", to));
                    } else if (status == ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS
                            || status == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                        // Already on their way somewhere: if it's back here, onServerPostConnect checks them again.
                    } else if (status == ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) {
                        skip.add(key(to));
                        moveOff(player, server, skip);
                    } else if (stillOn(player, server) && plugin.servers().inMaintenance(server)) {
                        player.disconnect(message(server));
                    }
                });
    }

    private static boolean stillOn(Player player, String server) {
        return player.isActive() && player.getCurrentServer()
                .map(c -> c.getServerInfo().getName().equalsIgnoreCase(server)).orElse(false);
    }

    // ---- Helpers ----

    /** What a player turned away from the server is told: its own message from the website, or the default one. */
    public Component message(String server) {
        String custom = plugin.servers().maintenanceMessage(server);
        return custom.isEmpty()
                ? plugin.messages().minecraft("minecraft.maintenance.default", "server", server)
                : plugin.messages().minecraft("minecraft.maintenance.custom", "server", server, "message", custom);
    }

    /**
     * The first server Velocity would try for this player that isn't in maintenance, skipping {@code skip} (lower
     * case), the one they're on and ones whose last ping got no answer. Like Velocity: the forced hosts for the
     * address they joined with, if it has any, else the try order.
     */
    Optional<RegisteredServer> nextServer(Player player, Set<String> skip) {
        Set<String> excluded = new HashSet<>(skip);
        player.getCurrentServer().ifPresent(c -> excluded.add(key(c.getServerInfo().getName())));
        for (String name : tryOrder(player)) {
            if (excluded.contains(key(name)) || plugin.servers().inMaintenance(name) || plugin.servers().unreachable(name)) {
                continue;
            }
            Optional<RegisteredServer> server = plugin.proxy().getServer(name);
            if (server.isPresent()) {
                return server;
            }
        }
        return Optional.empty();
    }

    private List<String> tryOrder(Player player) {
        String host = player.getVirtualHost().map(InetSocketAddress::getHostString).orElse("").toLowerCase(Locale.ROOT);
        Map<String, List<String>> forced = plugin.proxy().getConfiguration().getForcedHosts();
        List<String> servers = forced == null ? null : forced.get(host);
        return servers == null || servers.isEmpty() ? plugin.proxy().getConfiguration().getAttemptConnectionOrder() : servers;
    }

    private boolean inMaintenance(RegisteredServer server) {
        return plugin.servers().inMaintenance(server.getServerInfo().getName());
    }

    private static String key(String server) {
        return server.toLowerCase(Locale.ROOT);
    }
}
