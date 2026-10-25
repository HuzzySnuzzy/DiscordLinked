package com.huzzysnuzzy.discordlinked.listener;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.util.Optional;

public final class PlayerListener {

    public static final String BYPASS_PERMISSION = "discordlinked.bypass";

    private final DiscordLinked plugin;

    public PlayerListener(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    /** Blocks unlinked players and hands them a link code in the kick screen. */
    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        return EventTask.async(() -> {
            if (!event.getResult().isAllowed()) {
                return;
            }
            Player player = event.getPlayer();
            Optional<LinkedAccount> account;
            try {
                account = plugin.database().findByUuid(player.getUniqueId());
            } catch (SQLException e) {
                plugin.logger().error("Database lookup failed for {}", player.getUsername(), e);
                event.setResult(ComponentResult.denied(
                        Component.text("Account check failed, please try again shortly.")));
                return;
            }

            if (account.isPresent()) {
                if (!account.get().minecraftName().equals(player.getUsername())) {
                    try {
                        plugin.database().updateName(player.getUniqueId(), player.getUsername());
                    } catch (SQLException e) {
                        plugin.logger().warn("Could not update stored name for {}", player.getUsername(), e);
                    }
                }
                return;
            }
            if (player.hasPermission(BYPASS_PERMISSION)) {
                return;
            }

            String code = plugin.linkCodes().codeFor(player.getUniqueId(), player.getUsername());
            Component message = MiniMessage.miniMessage().deserialize(
                    plugin.config().kickMessage(), Placeholder.unparsed("code", code));
            event.setResult(ComponentResult.denied(message));
            plugin.audit().log(player.getUniqueId(), player.getUsername(), null,
                    EventTypes.DENIED_UNLINKED, "issued code, ip=" + ip(player));
        });
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        plugin.audit().log(player.getUniqueId(), player.getUsername(), discordId(player),
                EventTypes.JOIN, "ip=" + ip(player));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            return;
        }
        Player player = event.getPlayer();
        plugin.audit().log(player.getUniqueId(), player.getUsername(), discordId(player), EventTypes.LEAVE, null);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String from = event.getPreviousServer().map(s -> s.getServerInfo().getName()).orElse("(proxy)");
        plugin.audit().log(player.getUniqueId(), player.getUsername(), null, EventTypes.SERVER_SWITCH,
                from + " -> " + event.getServer().getServerInfo().getName());
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        if (!plugin.config().logChat()) {
            return;
        }
        Player player = event.getPlayer();
        plugin.audit().log(player.getUniqueId(), player.getUsername(), null, EventTypes.CHAT, event.getMessage());
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!plugin.config().logCommands() || !(event.getCommandSource() instanceof Player player)) {
            return;
        }
        plugin.audit().log(player.getUniqueId(), player.getUsername(), null, EventTypes.COMMAND,
                "/" + event.getCommand());
    }

    private Long discordId(Player player) {
        try {
            return plugin.database().findByUuid(player.getUniqueId()).map(LinkedAccount::discordId).orElse(null);
        } catch (SQLException e) {
            return null;
        }
    }

    private static String ip(Player player) {
        return player.getRemoteAddress().getAddress().getHostAddress();
    }
}
