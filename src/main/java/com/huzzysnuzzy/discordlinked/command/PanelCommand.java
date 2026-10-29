package com.huzzysnuzzy.discordlinked.command;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.discord.DiscordBot;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.util.Optional;

/**
 * /dla — sends a one-time, clickable login link for the website. Anyone can run it; linked players get a
 * link if their Discord account has the admin role, or is in the Discord server while web-member-access is on.
 * Unlinked players get the website's address to log in there with Discord (or are sent to /dla in Discord).
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
        if (plugin.web() == null) {
            player.sendMessage(plugin.messages().minecraft("minecraft.dla.offline"));
            return;
        }
        if (account.isEmpty()) {
            // No Discord account to log them in as: give them the address to log in with Discord there.
            String address = plugin.web().address();
            if (address == null) {
                player.sendMessage(plugin.messages().minecraft("minecraft.dla.starting"));
            } else if (plugin.web().discordLoginAvailable()) {
                player.sendMessage(plugin.messages().minecraft("minecraft.dla.unlinked",
                        Placeholder.component("link", plugin.messages().minecraft("minecraft.dla.link-text")
                                .clickEvent(ClickEvent.openUrl(address))
                                .hoverEvent(HoverEvent.showText(plugin.messages().minecraft("minecraft.dla.address-hover"))))));
            } else {
                player.sendMessage(plugin.messages().minecraft("minecraft.dla.unlinked-discord"));
            }
            return;
        }
        DiscordBot.MemberInfo member = plugin.bot().isReady() ? plugin.bot().checkMember(account.get().discordId()) : null;
        if (member == null || !(member.admin() || member.inGuild() && plugin.config().webMemberAccess())) {
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
