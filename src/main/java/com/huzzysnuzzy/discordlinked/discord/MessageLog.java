package com.huzzysnuzzy.discordlinked.discord;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.storage.DiscordMessage;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Saves the messages of the Discord channels picked on the website (message-log-channel-ids), for the settings
 * owner's eyes only. Edits keep the first version; deleted messages stay, marked as deleted.
 */
public final class MessageLog extends ListenerAdapter {

    /** A text channel the bot can see, for picking which ones to log. */
    public record Channel(long id, String name, String category, String type, boolean readable, boolean logged) {
    }

    private final DiscordLinked plugin;

    MessageLog(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    private boolean logged(GuildMessageChannel channel) {
        return channel.getGuild().getIdLong() == plugin.config().guildId()
                && plugin.config().messageLogChannelIds().contains(channel.getIdLong());
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || !logged(event.getGuildChannel())) {
            return;
        }
        Message message = event.getMessage();
        Member member = event.getMember();
        String name = member != null ? member.getEffectiveName() : event.getAuthor().getEffectiveName();
        String avatar = member != null ? member.getEffectiveAvatarUrl() : event.getAuthor().getEffectiveAvatarUrl();
        try {
            plugin.database().addDiscordMessage(new DiscordMessage(message.getIdLong(), event.getChannel().getIdLong(),
                    event.getAuthor().getIdLong(), name, avatar, event.getAuthor().isBot() || event.isWebhookMessage(),
                    text(message), attachments(message), message.getTimeCreated().toInstant().toEpochMilli(),
                    null, null, null), event.getChannel().getName());
        } catch (SQLException e) {
            plugin.logger().warn("Could not log a Discord message", e);
        }
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (!event.isFromGuild() || !logged(event.getGuildChannel())) {
            return;
        }
        Message message = event.getMessage();
        try {
            long when = message.getTimeEdited() != null ? message.getTimeEdited().toInstant().toEpochMilli()
                    : System.currentTimeMillis();
            plugin.database().editDiscordMessage(message.getIdLong(), text(message), when);
        } catch (SQLException e) {
            plugin.logger().warn("Could not log a Discord message edit", e);
        }
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        if (event.isFromGuild() && logged(event.getGuildChannel())) {
            deleted(List.of(event.getMessageIdLong()));
        }
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        if (logged(event.getChannel())) {
            deleted(event.getMessageIds().stream().map(Long::parseLong).toList());
        }
    }

    private void deleted(List<Long> ids) {
        try {
            plugin.database().deleteDiscordMessages(ids, System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.logger().warn("Could not log deleted Discord messages", e);
        }
    }

    /** The message as shown, plus what its embeds say (bots often only send embeds). */
    private static String text(Message message) {
        StringBuilder text = new StringBuilder(message.getContentDisplay());
        for (MessageEmbed embed : message.getEmbeds()) {
            List<String> parts = new ArrayList<>();
            if (embed.getTitle() != null) {
                parts.add(embed.getTitle());
            }
            if (embed.getDescription() != null) {
                parts.add(embed.getDescription());
            }
            if (!parts.isEmpty()) {
                text.append(text.isEmpty() ? "" : "\n").append("[embed] ").append(String.join(" — ", parts));
            }
        }
        if (!message.getStickers().isEmpty()) {
            text.append(text.isEmpty() ? "" : "\n").append("[sticker] ")
                    .append(message.getStickers().stream().map(s -> s.getName()).collect(Collectors.joining(", ")));
        }
        return text.toString();
    }

    private static String attachments(Message message) {
        if (message.getAttachments().isEmpty()) {
            return null;
        }
        return message.getAttachments().stream().map(Message.Attachment::getUrl).collect(Collectors.joining("\n"));
    }

    /** Every text channel in the Discord server, in Discord's order, and whether the bot can read it. */
    public List<Channel> channels(Guild guild) {
        List<Channel> list = new ArrayList<>();
        List<Long> logged = plugin.config().messageLogChannelIds();
        for (GuildChannel channel : guild.getChannels()) {
            if (!(channel instanceof GuildMessageChannel)) {
                continue;
            }
            String category = channel instanceof ICategorizableChannel c && c.getParentCategory() != null
                    ? c.getParentCategory().getName() : "";
            boolean readable = guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL);
            list.add(new Channel(channel.getIdLong(), channel.getName(), category,
                    channel.getType().name().toLowerCase(), readable, logged.contains(channel.getIdLong())));
        }
        return list;
    }
}
