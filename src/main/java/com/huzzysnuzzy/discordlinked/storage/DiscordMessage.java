package com.huzzysnuzzy.discordlinked.storage;

/**
 * A message from a logged Discord channel. {@code attachments} holds attachment URLs, one per line.
 * {@code original} is the first version of an edited message; {@code editedAt}/{@code deletedAt} are null if never.
 */
public record DiscordMessage(long id, long channelId, long authorId, String authorName, String authorAvatar, boolean bot,
                             String content, String attachments, long createdAt, Long editedAt, String original,
                             Long deletedAt) {
}
