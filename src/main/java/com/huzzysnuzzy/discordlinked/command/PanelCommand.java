package com.huzzysnuzzy.discordlinked.command;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.util.Optional;

/**
 * /dla — sends a one-time, clickable login link for the admin website. Anyone can run it; only players
 * whose linked Discord account has the admin role get a link.
 */
public final class PanelCommand implements SimpleCommand {

    private final DiscordLinked plugin;

    public PanelCommand(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(plugin.messages().minecraft("minecraft.dla.console"));
            return;
        }
        // The role check is a Discord API call, so keep it off the command thread.
        plugin.proxy().getScheduler().buildTask(plugin, () -> run(player)).schedule();
    }

    private void run(Player player) {
        Optional<LinkedAccount> account;
        try {
            account = plugin.database().findByUuid(player.getUniqueId());
        } catch (SQLException e) {
            plugin.logger().error("Database error in /dla", e);
            player.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
            return;
        }
        if (account.isEmpty()) {
            player.sendMessage(plugin.messages().minecraft("minecraft.dla.not-linked"));
            return;
        }
        if (plugin.web() == null) {
            player.sendMessage(plugin.messages().minecraft("minecraft.dla.offline"));
            return;
        }
        if (!plugin.bot().isReady() || !plugin.bot().hasAdminRole(account.get().discordId())) {
            player.sendMessage(plugin.messages().minecraft("minecraft.dla.no-access"));
            return;
        }
        String link = plugin.web().createLoginLink(account.get().discordId(),
                account.get().discordName() == null ? player.getUsername() : account.get().discordName(),
                account.get().discordAvatar());
        if (link == null) {
            player.sendMessage(plugin.messages().minecraft("minecraft.dla.starting"));
            return;
        }
        player.sendMessage(plugin.messages().minecraft("minecraft.dla.link",
                Placeholder.component("link", plugin.messages().minecraft("minecraft.dla.link-text")
                        .clickEvent(ClickEvent.openUrl(link))
                        .hoverEvent(HoverEvent.showText(plugin.messages().minecraft("minecraft.dla.link-hover"))))));
    }
}
