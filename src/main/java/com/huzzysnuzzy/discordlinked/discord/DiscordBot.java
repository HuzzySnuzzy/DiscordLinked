package com.huzzysnuzzy.discordlinked.discord;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.link.LinkCodeManager.PendingLink;
import com.huzzysnuzzy.discordlinked.storage.AuditEntry;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.awt.Color;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The Discord side: slash commands for linking and admin lookups, plus the log channel. */
public final class DiscordBot extends ListenerAdapter {

    private static final DefaultMemberPermissions ADMIN = DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS);

    private final DiscordLinked plugin;
    private JDA jda;
    private final Set<Long> profileRefreshes = ConcurrentHashMap.newKeySet();
    private final Set<Long> memberRefreshes = ConcurrentHashMap.newKeySet();
    private final Map<Long, CachedMember> memberCache = new ConcurrentHashMap<>();
    private static final Duration MEMBER_CACHE_TIME = Duration.ofMinutes(10);

    public DiscordBot(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.config().botConfigured()) {
            plugin.logger().warn("bot-token / guild-id not set in config.properties; the Discord bot is disabled "
                    + "and players will not be able to link.");
            return;
        }
        EnumSet<GatewayIntent> intents = GatewayIntent.getIntents(GatewayIntent.DEFAULT);
        if (plugin.config().chatBridgeEnabled() && plugin.config().discordToMinecraft()) {
            // Privileged: must also be switched on in the Developer Portal.
            intents.add(GatewayIntent.MESSAGE_CONTENT);
        }
        jda = JDABuilder.createLight(plugin.config().botToken(), intents)
                .addEventListeners(this)
                .build();
    }

    public void shutdown() {
        if (jda != null) {
            jda.shutdown();
        }
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        if (event.getCloseCode() == CloseCode.DISALLOWED_INTENTS) {
            plugin.logger().error("Discord refused the login: discord-to-minecraft=true needs \"Message Content Intent\". "
                    + "Turn it on at https://discord.com/developers/applications -> your app -> Bot -> "
                    + "Privileged Gateway Intents, then restart the proxy. Or set discord-to-minecraft=false.");
        } else if (event.getCloseCode() == CloseCode.AUTHENTICATION_FAILED) {
            plugin.logger().error("Discord refused the bot token. Check bot-token in config.properties.");
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
            event.reply(msg("discord.database-error")).setEphemeral(true).queue();
        }
    }

    /** Discord -> Minecraft side of the chat bridge. */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        PluginConfig config = plugin.config();
        if (!config.chatBridgeEnabled() || !config.discordToMinecraft()
                || event.getChannel().getIdLong() != config.chatChannelId()
                || event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }
        String content = event.getMessage().getContentDisplay().trim();
        if (content.isEmpty()) {
            return;
        }
        String name = event.getMember() != null ? event.getMember().getEffectiveName() : event.getAuthor().getEffectiveName();
        String minecraftName = name;
        try {
            Optional<LinkedAccount> account = plugin.database().findByDiscordId(event.getAuthor().getIdLong());
            if (account.isPresent()) {
                minecraftName = account.get().minecraftName();
            }
        } catch (SQLException e) {
            plugin.logger().warn("Could not look up linked account for bridged message", e);
        }
        plugin.proxy().sendMessage(plugin.messages().minecraft("minecraft.bridge.chat",
                "name", name, "minecraft_name", minecraftName, "message", content));
    }

    /** Minecraft -> Discord side of the chat bridge. Does nothing if disabled or the message is empty. */
    public void bridgeToDiscord(String key, String player, String server, String message) {
        PluginConfig config = plugin.config();
        if (jda == null || !config.chatBridgeEnabled() || !config.minecraftToDiscord() || plugin.messages().isEmpty(key)) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(config.chatChannelId());
        if (channel == null) {
            return;
        }
        String text = plugin.messages().discord(key,
                "player", MarkdownSanitizer.escape(player),
                "server", MarkdownSanitizer.escape(server),
                "message", MarkdownSanitizer.escape(message));
        channel.sendMessage(truncate(text)).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
    }

    private void handleLink(SlashCommandInteractionEvent event) throws SQLException {
        String code = event.getOption("code", OptionMapping::getAsString);
        Optional<PendingLink> pending = plugin.linkCodes().find(code);
        if (pending.isEmpty()) {
            event.reply(msg("discord.link.invalid-code")).setEphemeral(true).queue();
            return;
        }
        long discordId = event.getUser().getIdLong();
        Optional<LinkedAccount> existing = plugin.database().findByDiscordId(discordId);
        if (existing.isPresent()) {
            event.reply(msg("discord.link.already-linked-discord", "player", existing.get().minecraftName()))
                    .setEphemeral(true).queue();
            return;
        }
        PendingLink link = pending.get();
        if (plugin.database().findByUuid(link.uuid()).isPresent()) {
            plugin.linkCodes().consume(code);
            event.reply(msg("discord.link.already-linked-minecraft")).setEphemeral(true).queue();
            return;
        }

        plugin.database().link(link.uuid(), link.username(), discordId,
                event.getUser().getEffectiveName(), event.getUser().getEffectiveAvatarUrl());
        plugin.linkCodes().consume(code);
        addLinkedRole(discordId);
        plugin.audit().log(link.uuid(), link.username(), discordId, EventTypes.LINK, null);
        event.reply(msg("discord.link.success", "player", link.username())).setEphemeral(true).queue();
    }

    private void handleUnlink(SlashCommandInteractionEvent event) throws SQLException {
        Optional<LinkedAccount> account = plugin.database().findByDiscordId(event.getUser().getIdLong());
        if (account.isEmpty()) {
            event.reply(msg("discord.unlink.not-linked")).setEphemeral(true).queue();
            return;
        }
        plugin.unlink(account.get(), actor(event.getUser()), "Unlinked themselves with /unlink", false);
        event.reply(msg("discord.unlink.success", "player", account.get().minecraftName())).setEphemeral(true).queue();
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
            event.reply(msg("discord.whois.missing-argument")).setEphemeral(true).queue();
            return;
        }
        if (account.isEmpty()) {
            event.reply(msg("discord.not-found", "player", player != null ? player : user.getName()))
                    .setEphemeral(true).queue();
            return;
        }
        LinkedAccount a = account.get();
        boolean online = plugin.proxy().getPlayer(a.uuid()).isPresent();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(a.minecraftName())
                .setColor(online ? Color.GREEN : Color.GRAY)
                .addField(msg("discord.whois.field.discord"), "<@" + a.discordId() + "> (`" + a.discordId() + "`)", false)
                .addField(msg("discord.whois.field.uuid"), "`" + a.uuid() + "`", false)
                .addField(msg("discord.whois.field.linked"), "<t:" + a.linkedAt().getEpochSecond() + ":f>", true)
                .addField(msg("discord.whois.field.online"),
                        msg(online ? "discord.whois.online-yes" : "discord.whois.online-no"), true);
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void handleHistory(SlashCommandInteractionEvent event) throws SQLException {
        String player = event.getOption("player", OptionMapping::getAsString);
        Optional<LinkedAccount> account = plugin.database().findByName(player);
        if (account.isEmpty()) {
            event.reply(msg("discord.not-found", "player", player)).setEphemeral(true).queue();
            return;
        }
        List<AuditEntry> entries = plugin.database().recentEvents(account.get().uuid(), 20);
        StringBuilder body = new StringBuilder();
        for (AuditEntry entry : entries) {
            String line = plugin.messages().discord("discord.history.line",
                    "time", "<t:" + entry.timestamp().getEpochSecond() + ":R>",
                    "event", entry.eventType(),
                    "detail", entry.detail() == null ? "" : MarkdownSanitizer.escape(entry.detail())) + "\n";
            if (body.length() + line.length() > 4000) {
                break;
            }
            body.append(line);
        }
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(truncate(plugin.messages().discord("discord.history.title", "player", account.get().minecraftName()), 256))
                .setDescription(body.isEmpty() ? msg("discord.history.empty") : body.toString());
        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void handleForceUnlink(SlashCommandInteractionEvent event) throws SQLException {
        String player = event.getOption("player", OptionMapping::getAsString);
        Optional<LinkedAccount> account = plugin.database().findByName(player);
        if (account.isEmpty()) {
            event.reply(msg("discord.not-found", "player", player)).setEphemeral(true).queue();
            return;
        }
        plugin.unlink(account.get(), actor(event.getUser()), "Discord /forceunlink", true);
        event.reply(msg("discord.forceunlink.success", "player", account.get().minecraftName())).setEphemeral(true).queue();
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
            channel.sendMessage(truncate(message)).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
        }
    }

    public boolean isReady() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED && guild() != null;
    }

    /** The application's ID, which is also its OAuth2 client ID. */
    public String applicationId() {
        return jda.getSelfUser().getApplicationId();
    }

    /**
     * Whether the user is in the guild and has the admin role. Blocks on a REST call,
     * so never call this from a JDA thread.
     */
    public boolean hasAdminRole(long userId) {
        Guild guild = guild();
        long roleId = plugin.config().adminRoleId();
        if (guild == null || roleId == 0L) {
            return false;
        }
        try {
            Member member = guild.retrieveMemberById(userId).complete();
            cacheMember(userId, member);
            return isAdmin(member);
        } catch (ErrorResponseException e) {
            cacheMember(userId, null);
            return false; // not a member of the guild
        }
    }

    /**
     * Cached Discord membership for a user, or null if not known yet. A stale or missing entry is
     * refreshed in the background, so callers see the result on a later call.
     */
    public MemberInfo memberInfo(long userId) {
        CachedMember cached = memberCache.get(userId);
        if (cached == null || cached.fetchedAt().isBefore(Instant.now().minus(MEMBER_CACHE_TIME))) {
            refreshMember(userId);
        }
        return cached == null ? null : cached.info();
    }

    private void refreshMember(long userId) {
        Guild guild = guild();
        if (guild == null || !memberRefreshes.add(userId)) {
            return;
        }
        guild.retrieveMemberById(userId).queue(member -> {
            memberRefreshes.remove(userId);
            cacheMember(userId, member);
        }, error -> {
            memberRefreshes.remove(userId);
            if (error instanceof ErrorResponseException response
                    && (response.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER
                    || response.getErrorResponse() == ErrorResponse.UNKNOWN_USER)) {
                cacheMember(userId, null);
            }
        });
    }

    private void cacheMember(long userId, Member member) {
        memberCache.put(userId, new CachedMember(
                new MemberInfo(member != null, member != null && isAdmin(member)), Instant.now()));
        if (member != null) {
            try {
                plugin.database().updateDiscordProfile(userId, member.getUser().getEffectiveName(),
                        member.getUser().getEffectiveAvatarUrl());
            } catch (SQLException e) {
                plugin.logger().warn("Could not store Discord profile for {}", userId, e);
            }
        }
    }

    private boolean isAdmin(Member member) {
        long roleId = plugin.config().adminRoleId();
        return roleId != 0L && member.getRoles().stream().anyMatch(role -> role.getIdLong() == roleId);
    }

    /** Sends a direct message to a user. Failures (DMs closed, not in a shared server) are only logged. */
    public void sendDirectMessage(long userId, String text) {
        if (jda == null || text.isBlank()) {
            return;
        }
        jda.retrieveUserById(userId)
                .flatMap(User::openPrivateChannel)
                .flatMap(channel -> channel.sendMessage(truncate(text))
                        .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)))
                .queue(null, error -> plugin.logger().info("Could not DM Discord user {}: {}", userId, error.getMessage()));
    }

    /** {@code inGuild} is false when the user has left (or was never in) the Discord server. */
    public record MemberInfo(boolean inGuild, boolean admin) {
    }

    private record CachedMember(MemberInfo info, Instant fetchedAt) {
    }

    /** Fetches and stores a linked user's current Discord name and avatar in the background. */
    public void refreshProfile(long discordId) {
        if (jda == null || !profileRefreshes.add(discordId)) {
            return;
        }
        jda.retrieveUserById(discordId).queue(user -> {
            profileRefreshes.remove(discordId);
            try {
                plugin.database().updateDiscordProfile(discordId, user.getEffectiveName(), user.getEffectiveAvatarUrl());
            } catch (SQLException e) {
                plugin.logger().warn("Could not store Discord profile for {}", discordId, e);
            }
        }, error -> profileRefreshes.remove(discordId));
    }

    private static String actor(User user) {
        return user.getName() + " (" + user.getId() + ")";
    }

    /** A Discord message with player-supplied values markdown-escaped. */
    private String msg(String key, String... placeholders) {
        String[] escaped = placeholders.clone();
        for (int i = 1; i < escaped.length; i += 2) {
            escaped[i] = MarkdownSanitizer.escape(escaped[i]);
        }
        return truncate(plugin.messages().discord(key, escaped));
    }

    private static String truncate(String text) {
        return truncate(text, Message.MAX_CONTENT_LENGTH);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
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
