package com.huzzysnuzzy.discordlinked.backend;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.net.InetSocketAddress;

/**
 * Answers the proxy's ping (every 30 seconds) with this server's status, so the website knows this plugin is here,
 * its version and how the server is doing even while nobody is on it: plugin messages need a player, a ping
 * doesn't. DiscordLinked's proxy sends its pings to a host name that never resolves ({@link #PROBE_HOST}); only those
 * get the status as their MOTD, a single JSON object. Every other ping, from players' server lists or anything
 * else, is left exactly as it is.
 *
 * <p>Paper fires the event on a network thread, so this only reads what's safe from any thread, and it never lets an
 * exception out: if something goes wrong the ping is answered as usual and the proxy only learns the server is up.
 */
final class StatusPing implements Listener {

    /**
     * The host name in the proxy's pings (BackendServers.PROBE_HOST on the proxy). ".invalid" is reserved for names
     * that can't exist, so no player ever connects with it.
     */
    static final String PROBE_HOST = "discordlinked-status.invalid";

    private final DiscordLinkedBackend plugin;
    private volatile boolean warned;

    StatusPing(DiscordLinkedBackend plugin) {
        this.plugin = plugin;
    }

    /** Last, so a MOTD plugin can't overwrite the answer; it only touches the proxy's own pings. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPing(PaperServerListPingEvent event) {
        try {
            InetSocketAddress host = event.getClient().getVirtualHost();
            if (host == null || !PROBE_HOST.equalsIgnoreCase(host.getHostString())) {
                return;
            }
            Component answer = Component.text(json(plugin.link().currentStatus()));
            event.motd(answer);
            // Only DiscordLinked's proxy pings this host; a plugin that turns pings away mustn't make the server
            // look down on the website.
            event.setCancelled(false);
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Couldn't answer the proxy's ping with this server's status (" + e
                        + "). The website only sees this server once a player joins; please report this.");
            }
        }
    }

    /** {"discordlinked":1,"plugin":...}: the same values as STATUS. Unknown numbers are -1. */
    static String json(ProxyLink.Status status) {
        JsonObject json = new JsonObject();
        json.addProperty("discordlinked", 1);
        json.addProperty("plugin", status.plugin());
        json.addProperty("version", status.version());
        json.addProperty("minecraft", status.minecraft());
        json.addProperty("folia", status.folia());
        json.addProperty("online", status.online());
        json.addProperty("max", status.max());
        json.addProperty("tps", known(status.tps()));
        json.addProperty("mspt", known(status.mspt()));
        json.addProperty("spy", status.spy());
        return json.toString();
    }

    /** NaN or infinity isn't valid JSON (the proxy would ignore the whole answer); it means not known. */
    private static double known(double value) {
        return Double.isFinite(value) ? value : -1;
    }
}
