package com.huzzysnuzzy.discordlinked.discord;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.link.LinkCodeManager.PendingLink;
import com.huzzysnuzzy.discordlinked.storage.AuditEntry;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.awt.Color;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/** The Discord side: slash commands for linking and admin lookups, plus the log channel. */
public final class DiscordBot extends ListenerAdapter {

    private static final DefaultMemberPermissions ADMIN = DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS);

    private final DiscordLinked plugin;
    private JDA jda;

    public DiscordBot(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.config().botConfigured()) {
            plugin.logger().warn("bot-token / guild-id not set in config.properties; the Discord bot is disabled "
                    + "and players will not be able to link.");
            return;
        }
        jda = JDABuilder.createLight(plugin.config().botToken())
                .addEventListeners(this)
                .build();
    }

    public void shutdown() {
        if (jda != null) {
            jda.shutdown();
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        Guild guild = guild();
        if (guild == null) {
            plugin.logger().error("Bot is not in the guild {} set as guild-id. Invite it first.", plugin.config().guildId());
            return;
        }
        guild.updateCommands().addCommands(
                Commands.slash("link", "Link your Minecraft account using the code from the server")
                        .addOption(OptionType.STRING, "code", "The code shown when you tried to join", true),
                Commands.slash("unlink", "Unlink your Minecraft account"),
                Commands.slash("whois", "Look up a linked account")
                        .addOption(OptionType.STRING, "player", "Minecraft username", false)
                        .addOption(OptionType.USER, "user", "Discord user", false)
                        .setDefaultPermissions(ADMIN),
                Commands.slash("history", "Show recent activity for a player")
                        .addOption(OptionType.STRING, "player", "Minecraft username", true)
                        .setDefaultPermissions(ADMIN),
                Commands.slash("forceunlink", "Remove a player's link")
                        .addOption(OptionType.STRING, "player", "Minecraft username", true)
                        .setDefaultPermissions(ADMIN)
        ).queue();
        plugin.logger().info("Discord bot ready as {} in {}", event.getJDA().getSelfUser().getName(), guild.getName());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || event.getGuild().getIdLong() != plugin.config().guildId()) {
            return;
        }
        try {
            switch (event.getName()) {
                case "link" -> handleLink(event);
                case "unlink" -> handleUnlink(event);
                case "whois" -> handleWhois(event);
                case "history" -> handleHistory(event);
                case "forceunlink" -> handleForceUnlink(event);
                default -> { }
            }
        } catch (SQLException e) {
            plugin.logger().error("Database error handling /{}", event.getName(), e);
            event.reply("Something went wrong talking to the database.").setEphemeral(true).queue();
        }
    }

    private void handleLink(SlashCommandInteractionEvent event) throws SQLException {
        String code = event.getOption("code", OptionMapping::getAsString);
        Optional<PendingLink> pending = plugin.linkCodes().find(code);
        if (pending.isEmpty()) {
            event.reply("That code is invalid or expired. Join the Minecraft server again to get a new one.")
                    .setEphemeral(true).queue();
            return;
        }
        long discordId = event.getUser().getIdLong();
        Optional<LinkedAccount> existing = plugin.database().findByDiscordId(discordId);
        if (existing.isPresent()) {
            event.reply("Your Discord account is already linked to `" + existing.get().minecraftName()
                    + "`. Use /unlink first.").setEphemeral(true).queue();
            return;
        }
        PendingLink link = pending.get();
        if (plugin.database().findByUuid(link.uuid()).isPresent()) {
            plugin.linkCodes().consume(code);
            event.reply("That Minecraft account is already linked.").setEphemeral(true).queue();
            return;
        }

        plugin.database().link(link.uuid(), link.username(), discordId);
        plugin.linkCodes().consume(code);
        addLinkedRole(discordId);
        plugin.audit().log(link.uuid(), link.username(), discordId, EventTypes.LINK, null);
        event.reply("Linked to **" + MarkdownSanitizer.escape(link.username()) + "**. You can join the server now!")
                .setEphemeral(true).queue();
    }

    private void handleUnlink(SlashCommandInteractionEvent event) throws SQLException {
        Optional<LinkedAccount> account = plugin.database().findByDiscordId(event.getUser().getIdLong());
        if (account.isEmpty()) {
            event.reply("Your Discord account isn't linked.").setEphemeral(true).queue();
            return;
        }
        plugin.unlink(account.get(), "self (Discord)");
        event.reply("Unlinked from `" + account.get().minecraftName() + "`.").setEphemeral(true).queue();
    }

    private void handleWhois(SlashCommandInteractionEvent event) throws SQLException {
        String player = event.getOption("player", OptionMapping::getAsString);
        User user = event.getOption("user", OptionMapping::getAsUser);
        Optional<LinkedAccount> account;
        if (player != null) {
            account = plugin.database().findByName(player);
        } else if (user != null) {
            account = plugin.database().findByDiscordId(user.getIdLong());
        } else {
            event.reply("Give a `player` or a `user`.").setEphemeral(true).queue();
            return;
        }
        if (account.isEmpty()) {
            event.reply("No linked account found.").setEphemeral(true).queue();
            return;
        }
        LinkedAccount a = account.get();
        boolean online = plugin.proxy().getPlayer(a.uuid()).isPresent();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(a.minecraftName())
                .setColor(online ? Color.GREEN : Color.GRAY)
                .addField("Discord", "<@" + a.discordId() + "> (`" + a.discordId() + "`)", false)
                .addField("UUID", "`" + a.uuid() + "`", false)
                .addField("Linked", "<t:" + a.linkedAt().getEpochSecond() + ":f>", true)
                .addField("Online", online ? "Yes" : "No", true);
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void handleHistory(SlashCommandInteractionEvent event) throws SQLException {
        String player = event.getOption("player", OptionMapping::getAsString);
        Optional<LinkedAccount> account = plugin.database().findByName(player);
        if (account.isEmpty()) {
            event.reply("No linked account found for `" + MarkdownSanitizer.sanitize(player) + "`.")
                    .setEphemeral(true).queue();
            return;
        }
        List<AuditEntry> entries = plugin.database().recentEvents(account.get().uuid(), 20);
        StringBuilder body = new StringBuilder();
        for (AuditEntry entry : entries) {
            String line = "<t:" + entry.timestamp().getEpochSecond() + ":R> **" + entry.eventType() + "**"
                    + (entry.detail() == null ? "" : " " + MarkdownSanitizer.escape(entry.detail())) + "\n";
            if (body.length() + line.length() > 4000) {
                break;
            }
            body.append(line);
        }
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Recent activity: " + account.get().minecraftName())
                .setDescription(body.isEmpty() ? "No activity recorded." : body.toString());
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void handleForceUnlink(SlashCommandInteractionEvent event) throws SQLException {
        String player = event.getOption("player", OptionMapping::getAsString);
        Optional<LinkedAccount> account = plugin.database().findByName(player);
        if (account.isEmpty()) {
            event.reply("No linked account found.").setEphemeral(true).queue();
            return;
        }
        plugin.unlink(account.get(), "admin " + event.getUser().getName() + " (Discord)");
        event.reply("Unlinked `" + account.get().minecraftName() + "`.").setEphemeral(true).queue();
    }

    public void addLinkedRole(long discordId) {
        Role role = linkedRole();
        if (role != null) {
            role.getGuild().addRoleToMember(UserSnowflake.fromId(discordId), role).queue(null,
                    error -> plugin.logger().warn("Could not give linked role to {}: {}", discordId, error.getMessage()));
        }
    }

    public void removeLinkedRole(long discordId) {
        Role role = linkedRole();
        if (role != null) {
            role.getGuild().removeRoleFromMember(UserSnowflake.fromId(discordId), role).queue(null,
                    error -> plugin.logger().warn("Could not remove linked role from {}: {}", discordId, error.getMessage()));
        }
    }

    /** Posts to the configured log channel, if any. Never pings anyone. */
    public void sendLog(String message) {
        if (jda == null || plugin.config().logChannelId() == 0L) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(plugin.config().logChannelId());
        if (channel != null) {
            channel.sendMessage(message).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
        }
    }

    private Guild guild() {
        return jda == null ? null : jda.getGuildById(plugin.config().guildId());
    }

    private Role linkedRole() {
        Guild guild = guild();
        if (guild == null || plugin.config().linkedRoleId() == 0L) {
            return null;
        }
        return guild.getRoleById(plugin.config().linkedRoleId());
    }
}
