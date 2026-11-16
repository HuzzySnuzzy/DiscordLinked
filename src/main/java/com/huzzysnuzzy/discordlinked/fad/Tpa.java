package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.Home;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Teleport requests: /tpa Name (to go to them), /tpahere Name (to bring them here), answered with /tpaccept or
 * /tpdeny, taken back with /tpacancel; /tpatoggle stops (or allows again) requests to yourself. Decided here so it
 * works across servers. They only work on servers where the settings owner switched them on, and expire after
 * tpa-expire-seconds. The teleport itself is the one /home uses (the same wait, effects and moving servers).
 */
public final class Tpa {

    /** {@code here}: /tpahere (the one asked comes to the one asking). */
    record Request(UUID from, String fromName, UUID to, String toName, boolean here, long at) {

        UUID mover() {
            return here ? to : from;
        }

        UUID destination() {
            return here ? from : to;
        }
    }

    private final DiscordLinked plugin;
    private final Community community;
    /** Every open request; each player has at most one going out. */
    private final List<Request> open = new ArrayList<>();

    Tpa(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
    }

    private PluginConfig config() {
        return plugin.config();
    }

    /** Whether /tpa works on this server. */
    public boolean enabled(String server) {
        return config().fadEnabled() && plugin.servers().tpaEnabled(server);
    }

    /** From the backend: /tpa, /tpahere, /tpaccept, /tpdeny, /tpacancel or /tpatoggle. */
    void command(Player player, String server, String type, String argument) {
        String arg = argument == null ? "" : argument.trim();
        switch (type) {
            case "tpa" -> request(player, server, arg, false);
            case "tpahere" -> request(player, server, arg, true);
            case "accept" -> accept(player, server, arg);
            case "deny" -> deny(player, server, arg);
            case "cancel" -> cancel(player, server);
            case "toggle" -> toggle(player, server);
            default -> { }
        }
    }

    // ---- Asking ----

    private void request(Player from, String server, String targetName, boolean here) {
        if (!usable(from, server)) {
            return;
        }
        String usage = here ? "minecraft.tpa.usage-here" : "minecraft.tpa.usage";
        if (targetName.isEmpty()) {
            say(from, usage);
            return;
        }
        Optional<Player> found = plugin.proxy().getPlayer(targetName);
        if (found.isEmpty()) {
            say(from, "minecraft.tpa.not-online", "player", targetName);
            return;
        }
        Player to = found.get();
        if (to.getUniqueId().equals(from.getUniqueId())) {
            say(from, "minecraft.tpa.self");
            return;
        }
        String toServer = serverOf(to);
        if (!enabled(toServer)) {
            say(from, "minecraft.tpa.off-there", "player", to.getUsername(), "server", toServer);
            return;
        }
        try {
            if (!plugin.database().tpaAllowed(to.getUniqueId())) {
                say(from, "minecraft.tpa.blocked", "player", to.getUsername());
                return;
            }
        } catch (SQLException e) {
            plugin.logger().error("Could not look up whether {} takes teleport requests", to.getUsername(), e);
            say(from, "minecraft.command.database-error");
            return;
        }
        Request request = new Request(from.getUniqueId(), from.getUsername(), to.getUniqueId(), to.getUsername(), here,
                System.currentTimeMillis());
        synchronized (this) {
            sweep();
            Optional<Request> same = open.stream().filter(r -> r.from().equals(request.from())
                    && r.to().equals(request.to()) && r.here() == here).findFirst();
            if (same.isPresent()) {
                say(from, "minecraft.tpa.already", "player", to.getUsername());
                return;
            }
            // A new request replaces the one they had out.
            open.removeIf(r -> r.from().equals(request.from()));
            open.add(request);
        }
        int seconds = config().tpaExpireSeconds();
        from.sendMessage(plugin.messages().minecraft("minecraft.tpa.sent",
                Placeholder.component("cancel", button("minecraft.tpa.cancel-button", "/tpacancel", "minecraft.tpa.cancel-hover",
                        to.getUsername())),
                "player", to.getUsername(), "seconds", String.valueOf(seconds)));
        to.sendMessage(plugin.messages().minecraft(here ? "minecraft.tpa.asked-here" : "minecraft.tpa.asked",
                TagResolver.resolver(
                        Placeholder.component("accept", button("minecraft.tpa.accept-button", "/tpaccept " + from.getUsername(),
                                "minecraft.tpa.accept-hover", from.getUsername())),
                        Placeholder.component("deny", button("minecraft.tpa.deny-button", "/tpdeny " + from.getUsername(),
                                "minecraft.tpa.deny-hover", from.getUsername()))),
                "player", from.getUsername(), "seconds", String.valueOf(seconds)));
        plugin.proxy().getScheduler().buildTask(plugin, () -> expire(request)).delay(seconds, TimeUnit.SECONDS).schedule();
    }

    private void expire(Request request) {
        synchronized (this) {
            if (!open.remove(request)) {
                return; // answered or taken back
            }
        }
        plugin.proxy().getPlayer(request.from()).ifPresent(p -> say(p, "minecraft.tpa.expired", "player", request.toName()));
        plugin.proxy().getPlayer(request.to()).ifPresent(p -> say(p, "minecraft.tpa.expired-to", "player", request.fromName()));
    }

    // ---- Answering ----

    /** The request to {@code to} from {@code name} (empty: the newest one), taken off the list. */
    private Request take(Player to, String name) {
        synchronized (this) {
            sweep();
            Request found = null;
            for (Request r : open) {
                if (r.to().equals(to.getUniqueId()) && (name.isEmpty() || r.fromName().equalsIgnoreCase(name))
                        && (found == null || r.at() > found.at())) {
                    found = r;
                }
            }
            if (found != null) {
                open.remove(found);
            }
            return found;
        }
    }

    private void accept(Player to, String server, String name) {
        if (!usable(to, server)) {
            return;
        }
        Request request = take(to, name);
        if (request == null) {
            say(to, name.isEmpty() ? "minecraft.tpa.none" : "minecraft.tpa.none-from", "player", name);
            return;
        }
        Optional<Player> from = plugin.proxy().getPlayer(request.from());
        if (from.isEmpty()) {
            say(to, "minecraft.tpa.gone", "player", request.fromName());
            return;
        }
        Player mover = request.here() ? to : from.get();
        Player destination = request.here() ? from.get() : to;
        say(to, "minecraft.tpa.accepted", "player", request.fromName());
        say(from.get(), "minecraft.tpa.accepted-from", "player", request.toName());
        Optional<ServerConnection> there = destination.getCurrentServer();
        String destinationServer = serverOf(destination);
        if (there.isEmpty() || !enabled(destinationServer) || !enabled(serverOf(mover))) {
            say(mover, "minecraft.tpa.off-there", "player", destination.getUsername(), "server", destinationServer);
            return;
        }
        if (!plugin.servers().deliversContainers(destinationServer)) {
            say(mover, "minecraft.tpa.needs-backend", "server", destinationServer);
            return;
        }
        // Where the destination stands right now; then the same trip as /home.
        community.homes().locate(there.get()).orTimeout(5, TimeUnit.SECONDS).whenComplete((at, error) -> {
            if (error != null || at == null) {
                say(mover, "minecraft.tpa.failed", "player", destination.getUsername());
                return;
            }
            Home spot = new Home(destination.getUniqueId(), destination.getUsername(), "", destinationServer, at.world(),
                    at.x(), at.y(), at.z(), at.yaw(), at.pitch(), Instant.now(), Instant.now());
            plugin.audit().log(mover.getUniqueId(), mover.getUsername(), community.linkedDiscordId(mover.getUniqueId()),
                    EventTypes.HOME, (request.here() ? "/tpahere: " + request.fromName() + " brought " + request.toName()
                            : "/tpa: " + request.fromName() + " went to " + request.toName()) + " (accepted)",
                    null, destinationServer);
            community.homes().travel(mover, spot);
        });
    }

    private void deny(Player to, String server, String name) {
        Request request = take(to, name);
        if (request == null) {
            say(to, name.isEmpty() ? "minecraft.tpa.none" : "minecraft.tpa.none-from", "player", name);
            return;
        }
        say(to, "minecraft.tpa.denied", "player", request.fromName());
        plugin.proxy().getPlayer(request.from()).ifPresent(p -> say(p, "minecraft.tpa.denied-from", "player", request.toName()));
    }

    private void cancel(Player from, String server) {
        Request request;
        synchronized (this) {
            sweep();
            request = open.stream().filter(r -> r.from().equals(from.getUniqueId())).findFirst().orElse(null);
            if (request != null) {
                open.remove(request);
            }
        }
        if (request == null) {
            say(from, "minecraft.tpa.nothing-to-cancel");
            return;
        }
        say(from, "minecraft.tpa.cancelled", "player", request.toName());
        plugin.proxy().getPlayer(request.to()).ifPresent(p -> say(p, "minecraft.tpa.cancelled-to", "player", request.fromName()));
    }

    /** /tpatoggle: no more requests to them (and the open ones gone), or requests allowed again. */
    private void toggle(Player player, String server) {
        if (!usable(player, server)) {
            return;
        }
        try {
            boolean allowed = !plugin.database().tpaAllowed(player.getUniqueId());
            plugin.database().setTpaAllowed(player.getUniqueId(), player.getUsername(), allowed);
            if (!allowed) {
                synchronized (this) {
                    open.removeIf(r -> r.to().equals(player.getUniqueId()));
                }
            }
            say(player, allowed ? "minecraft.tpa.toggle-on" : "minecraft.tpa.toggle-off");
        } catch (SQLException e) {
            plugin.logger().error("Could not switch teleport requests for {}", player.getUsername(), e);
            say(player, "minecraft.command.database-error");
        }
    }

    /** The player left the network: their requests go. */
    void left(UUID player) {
        synchronized (this) {
            open.removeIf(r -> r.from().equals(player) || r.to().equals(player));
        }
    }

    // ---- Helpers ----

    private boolean usable(Player player, String server) {
        if (!enabled(server)) {
            say(player, "minecraft.tpa.off");
            return false;
        }
        return true;
    }

    /** Forgets requests that ran out (their expiry task normally does it). */
    private void sweep() {
        long oldest = System.currentTimeMillis() - config().tpaExpireSeconds() * 1000L;
        open.removeIf(r -> r.at() < oldest);
    }

    private static String serverOf(Player player) {
        return player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
    }

    private Component button(String key, String command, String hoverKey, String name) {
        return plugin.messages().minecraft(key, "player", name)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(plugin.messages().minecraft(hoverKey, "player", name)));
    }

    private void say(Player player, String key, String... placeholders) {
        if (!plugin.messages().isEmpty(key)) {
            player.sendMessage(plugin.messages().minecraft(key, placeholders));
        }
    }
}
