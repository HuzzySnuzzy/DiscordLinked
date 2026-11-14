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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * /dla — sends a one-time, clickable login link for the website. Anyone can run it; linked players get a
 * link if their Discord account has the admin role, or is in the Discord server while web-member-access is on.
 * Unlinked players get the website's address to log in there with Discord (or are sent to /dla in Discord).
 * {@code /dla shop} is the same link, opening the Shop; {@code /dla reminders on|off} switches the player's in-game
 * Shop reminders (see {@link com.huzzysnuzzy.discordlinked.fad.ShopReminder}).
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
        String[] args = invocation.arguments();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (first.equals("reminders")) {
            String state = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
            plugin.proxy().getScheduler().buildTask(plugin, () -> reminders(player, state)).schedule();
            return;
        }
        String page = first.equals("shop") ? "shop" : null;
        // The role check is a Discord API call, so keep it off the command thread.
        plugin.proxy().getScheduler().buildTask(plugin, () -> run(player, page)).schedule();
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return Stream.of("shop", "reminders").filter(option -> option.startsWith(typed)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reminders")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return Stream.of("on", "off").filter(option -> option.startsWith(typed)).toList();
        }
        return List.of();
    }

    /** /dla reminders on|off: the player's own in-game Shop reminders. */
    private void reminders(Player player, String state) {
        try {
            boolean on;
            if (state.equals("on") || state.equals("off")) {
                on = state.equals("on");
                plugin.database().setShopReminders(player.getUniqueId(), player.getUsername(), on);
            } else {
                on = plugin.database().shopReminders(player.getUniqueId());
            }
            player.sendMessage(plugin.messages().minecraft(on ? "minecraft.dla.reminders-on" : "minecraft.dla.reminders-off"));
        } catch (SQLException e) {
            plugin.logger().error("Database error in /dla reminders", e);
            player.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
        }
    }

    /** {@code page}: what the link opens ("shop"), or null for the website's home page. */
    private void run(Player player, String page) {
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
                account.get().discordAvatar(), page);
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
