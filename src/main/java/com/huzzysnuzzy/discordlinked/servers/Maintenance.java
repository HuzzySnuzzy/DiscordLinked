package com.huzzysnuzzy.discordlinked.servers;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.discord.DiscordBot;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Maintenance mode: a server in maintenance (Settings -> Backend servers, or /dlink maintenance) only lets admins in.
 * Who counts as one is decided in {@link #mayJoin}. Everyone else is turned away with the server's message: in chat
 * when they're on another server, or sent to the next server Velocity would try (its forced hosts for the address
 * they joined with, else its try order) when it's the server they join on or fall back to after a kick, and
 * disconnected with the message when there's none. Turning maintenance on moves the players already there.
 *
 * <p>The listeners run last ({@link Short#MIN_VALUE}), so they see the server other plugins picked. Deciding needs the
 * database, and Discord when the admin role isn't cached, so it runs in an {@link EventTask#async} task, and only for
 * servers in maintenance.
 */
public final class Maintenance {

    /** Lets a player join servers in maintenance, without the admin role. */
    public static final String BYPASS_PERMISSION = "discordlinked.maintenance.bypass";
    /** How long to wait for Discord when a player's admin role isn't cached yet. Not answered in time = not an admin. */
    private static final long DISCORD_WAIT_SECONDS = 4;
    /** How long moving one player off a server may take before they're disconnected instead. */
    private static final long MOVE_WAIT_SECONDS = 15;

    private final DiscordLinked plugin;

    public Maintenance(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    // ---- Who may join ----

    /**
     * Whether the player may join servers in maintenance: they have {@link #BYPASS_PERMISSION}, or their linked
     * Discord account is the website's settings owner (web-owner-id, else the Discord server's owner) or has the
     * admin role. Can block on the database and on Discord (at most {@link #DISCORD_WAIT_SECONDS}), so never call it
     * on an event thread.
     */
    public boolean mayJoin(Player player) {
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return true;
        }
        Optional<LinkedAccount> account;
        try {
            account = plugin.database().findByUuid(player.getUniqueId());
        } catch (SQLException e) {
            plugin.logger().warn("Could not check whether {} may join a server in maintenance", player.getUsername(), e);
            return false;
        }
        if (account.isEmpty()) {
            return false;
        }
        long discordId = account.get().discordId();
        long owner = plugin.settingsOwnerId();
        if (owner != 0L && owner == discordId) {
            return true;
        }
        DiscordBot bot = plugin.bot();
        DiscordBot.MemberInfo member = bot.memberInfo(discordId);
        if (member == null && bot.isReady()) {
            try {
                member = CompletableFuture.supplyAsync(() -> bot.checkMember(discordId))
                        .get(DISCORD_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                plugin.logger().warn("Discord didn't say in time whether {} has the admin role, so they can't join a "
                        + "server in maintenance this time", player.getUsername());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return member != null && member.admin();
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

    /** Kicked from a server and about to be sent to one in maintenance: the next server instead, or disconnected. */
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
            Optional<RegisteredServer> next = nextServer(player,
                    Set.of(key(event.getServer().getServerInfo().getName()), key(name)));
            Component note = redirect.getMessageComponent();
            if (next.isPresent()) {
                event.setResult(note == null ? KickedFromServerEvent.RedirectPlayer.create(next.get())
                        : KickedFromServerEvent.RedirectPlayer.create(next.get(), note));
            } else {
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                        event.getServerKickReason().orElseGet(() -> message(name))));
            }
        });
    }

    /**
     * Maintenance was just turned on: everyone on the server who may not stay goes to the next server (and is told
     * why), or is disconnected with the message when there's none or the move fails. Returns at once.
     */
    public void moveOff(String server) {
        for (Player player : plugin.proxy().getAllPlayers()) {
            boolean there = player.getCurrentServer()
                    .map(c -> c.getServerInfo().getName().equalsIgnoreCase(server)).orElse(false);
            if (there) {
                plugin.proxy().getScheduler().buildTask(plugin, () -> moveOff(player, server)).schedule();
            }
        }
    }

    private void moveOff(Player player, String server) {
        if (!stillOn(player, server) || mayJoin(player)) {
            return;
        }
        Component message = message(server);
        Optional<RegisteredServer> next = nextServer(player, Set.of(key(server)));
        if (next.isEmpty()) {
            player.disconnect(message);
            return;
        }
        String to = next.get().getServerInfo().getName();
        boolean moved;
        try {
            moved = player.createConnectionRequest(next.get()).connect()
                    .get(MOVE_WAIT_SECONDS, TimeUnit.SECONDS).isSuccessful();
        } catch (TimeoutException | ExecutionException e) {
            moved = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            moved = false;
        }
        if (moved) {
            player.sendMessage(message);
            player.sendMessage(plugin.messages().minecraft("minecraft.maintenance.moved", "server", server, "to", to));
        } else if (stillOn(player, server) && plugin.servers().inMaintenance(server)) {
            player.disconnect(message);
        }
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
     * case) and the one they're on. Like Velocity: the forced hosts for the address they joined with, if it has any,
     * else the try order.
     */
    Optional<RegisteredServer> nextServer(Player player, Set<String> skip) {
        Set<String> excluded = new HashSet<>(skip);
        player.getCurrentServer().ifPresent(c -> excluded.add(key(c.getServerInfo().getName())));
        for (String name : tryOrder(player)) {
            if (excluded.contains(key(name)) || plugin.servers().inMaintenance(name)) {
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
