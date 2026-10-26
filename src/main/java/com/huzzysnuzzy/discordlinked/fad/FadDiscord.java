package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.CreditAccount;
import com.huzzysnuzzy.discordlinked.storage.LeaderboardRow;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.awt.Color;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Discord side of the community features: every interaction from the Minecraft servers' config.yml as a slash
 * command (/hug @someone, shown in game too), plus /credits, /leaderboard and /interactions.
 */
public final class FadDiscord {

    /** Names DiscordLinked already uses; an interaction with one of these names stays in-game only. */
    private static final Set<String> RESERVED = Set.of("link", "unlink", "whois", "history", "forceunlink", "dla",
            "credits", "leaderboard", "interactions");
    /** Discord allows 100 commands per server; leave room for the built-in ones and other bots' needs. */
    private static final int MAX_COMMANDS = 90;
    private static final Color COLOR = new Color(0xFF9BD2);

    private final DiscordLinked plugin;
    private final Community community;
    private final Map<Long, Long> lastUse = new ConcurrentHashMap<>();

    FadDiscord(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
    }

    /** The community slash commands; none when the features are turned off. */
    public List<SlashCommandData> commands() {
        PluginConfig c = plugin.config();
        List<SlashCommandData> list = new ArrayList<>();
        if (!c.fadEnabled()) {
            return list;
        }
        list.add(Commands.slash("credits", limit("Show how many " + c.creditsNamePlural() + " someone has"))
                .addOption(OptionType.USER, "player", "Whose to show (default: yours)", false));
        list.add(Commands.slash("leaderboard", limit("Who has the most " + c.creditsNamePlural())));
        list.add(Commands.slash("interactions", "Turn interactions like /hug and /kiss off or on for yourself")
                .addOption(OptionType.BOOLEAN, "enabled", "On or off", true));
        FadActions.Definitions definitions = community.actions().current();
        if (!c.fadDiscordCommands() || definitions == null) {
            return list;
        }
        for (FadActions.Action action : definitions.actions().values()) {
            if (RESERVED.contains(action.name()) || !action.name().matches("[-_\\p{Ll}\\p{N}]{1,32}")
                    || list.size() >= MAX_COMMANDS) {
                continue;
            }
            SlashCommandData command = Commands.slash(action.name(), limit(action.description()));
            if (action.takesTarget()) {
                command.addOption(OptionType.USER, "player", action.requiresTarget() ? "Who" : "Who (optional)",
                        action.requiresTarget());
            }
            list.add(command);
        }
        return list;
    }

    /** Handles a community slash command. Returns false if it isn't one. */
    public boolean handle(SlashCommandInteractionEvent event) throws SQLException {
        switch (event.getName()) {
            case "credits" -> credits(event);
            case "leaderboard" -> leaderboard(event);
            case "interactions" -> interactions(event);
            default -> {
                FadActions.Definitions definitions = community.actions().current();
                FadActions.Action action = definitions == null || RESERVED.contains(event.getName())
                        ? null : definitions.actions().get(event.getName());
                if (action == null) {
                    return false;
                }
                interact(event, definitions, action);
            }
        }
        return true;
    }

    private void interact(SlashCommandInteractionEvent event, FadActions.Definitions definitions, FadActions.Action action)
            throws SQLException {
        PluginConfig c = plugin.config();
        if (!c.fadEnabled() || !c.fadDiscordCommands()) {
            event.reply(msg("discord.fad.disabled")).setEphemeral(true).queue();
            return;
        }
        User actor = event.getUser();
        User target = event.getOption("player", OptionMapping::getAsUser);
        Member targetMember = event.getOption("player", OptionMapping::getAsMember);
        boolean self = target != null && target.getIdLong() == actor.getIdLong();
        if (self && !definitions.allowSelf()) {
            event.reply(msg("discord.fad.self")).setEphemeral(true).queue();
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastUse.get(actor.getIdLong());
        long cooldownMs = definitions.cooldownSeconds() * 1000L;
        if (last != null && now - last < cooldownMs) {
            event.reply(msg("discord.fad.cooldown", "seconds", String.valueOf((cooldownMs - (now - last) + 999) / 1000)))
                    .setEphemeral(true).queue();
            return;
        }
        Optional<LinkedAccount> actorLink = plugin.database().findByDiscordId(actor.getIdLong());
        Optional<LinkedAccount> targetLink = target == null ? Optional.empty()
                : plugin.database().findByDiscordId(target.getIdLong());
        if (target != null && !self && community.isOptedOut(targetLink.map(LinkedAccount::uuid).orElse(null),
                target.getIdLong())) {
            event.reply(msg("discord.fad.opted-out", "user", "<@" + target.getId() + ">")).setEphemeral(true)
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
            return;
        }
        lastUse.put(actor.getIdLong(), now);

        // In game, linked members show by their Minecraft name.
        Member actorMember = event.getMember();
        String actorName = actorLink.map(LinkedAccount::minecraftName)
                .orElse(actorMember != null ? actorMember.getEffectiveName() : actor.getEffectiveName());
        String targetName = target == null ? null : targetLink.map(LinkedAccount::minecraftName)
                .orElse(targetMember != null ? targetMember.getEffectiveName() : target.getEffectiveName());
        List<String> lines = action.lines(target != null);
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));

        String text = FadActions.discord(line, "<@" + actor.getId() + ">", target == null ? null : "<@" + target.getId() + ">");
        String post = plugin.messages().isEmpty("discord.fad.interaction") ? text
                : plugin.messages().discord("discord.fad.interaction", "message", text, "action", action.name());
        ReplyCallbackAction reply = event.reply(truncate(post)).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
        if (c.fadDiscordPing() && target != null && !self) {
            reply = reply.mentionUsers(target.getIdLong());
        }
        reply.queue();

        plugin.proxy().sendMessage(community.fromDiscord(FadActions.minecraft(definitions.format(),
                definitions.nameFormat(), line, actorName, targetName)));
        UUID targetUuid = targetLink.map(LinkedAccount::uuid).orElse(null);
        community.record(action.name(), actorLink.map(LinkedAccount::uuid).orElse(null), actor.getIdLong(), actorName,
                targetUuid, target == null ? null : target.getIdLong(), targetName, "discord",
                FadActions.plain(line, actorName, targetName), "Discord");
        if (targetUuid != null) {
            plugin.proxy().getPlayer(targetUuid).ifPresent(player -> community.bridge().effects(player, action.name()));
        }
    }

    private void credits(SlashCommandInteractionEvent event) throws SQLException {
        PluginConfig c = plugin.config();
        if (!c.fadEnabled()) {
            event.reply(msg("discord.fad.disabled")).setEphemeral(true).queue();
            return;
        }
        User user = event.getOption("player", event.getUser(), OptionMapping::getAsUser);
        Optional<LinkedAccount> link = plugin.database().findByDiscordId(user.getIdLong());
        if (link.isEmpty()) {
            event.reply(msg("discord.fad.not-linked", "user", "<@" + user.getId() + ">"))
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
            return;
        }
        UUID uuid = link.get().uuid();
        Optional<CreditAccount> account = plugin.database().creditAccount(uuid);
        long rank = plugin.database().creditRank(uuid);
        event.reply(msg("discord.fad.balance",
                        "player", link.get().minecraftName(),
                        "balance", c.credits(account.map(CreditAccount::credits).orElse(0L)),
                        "rank", rank == 0 ? "-" : String.valueOf(rank),
                        "playtime", Community.playtime(account.map(CreditAccount::activeSeconds).orElse(0L)),
                        "advancements", String.valueOf(plugin.database().advancements(uuid).size())))
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
    }

    private void leaderboard(SlashCommandInteractionEvent event) throws SQLException {
        PluginConfig c = plugin.config();
        if (!c.fadEnabled()) {
            event.reply(msg("discord.fad.disabled")).setEphemeral(true).queue();
            return;
        }
        List<LeaderboardRow> top = plugin.database().leaderboard("credits", 10);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            body.append(msg("discord.fad.top-line", "rank", String.valueOf(i + 1), "player", top.get(i).name(),
                    "balance", c.credits(top.get(i).credits()))).append('\n');
        }
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(COLOR)
                .setTitle(truncate(msg("discord.fad.top-title", "currency", c.creditsNamePlural()), 256))
                .setDescription(top.isEmpty() ? msg("discord.fad.top-empty", "currency", c.creditsNamePlural())
                        : truncate(body.toString(), 4000));
        event.replyEmbeds(embed.build()).queue();
    }

    private void interactions(SlashCommandInteractionEvent event) throws SQLException {
        boolean enabled = event.getOption("enabled", true, OptionMapping::getAsBoolean);
        long discordId = event.getUser().getIdLong();
        UUID uuid = plugin.database().findByDiscordId(discordId).map(LinkedAccount::uuid).orElse(null);
        plugin.database().setOptOut(Community.subjects(uuid, discordId), !enabled);
        event.reply(msg(enabled ? "discord.fad.interactions-on" : "discord.fad.interactions-off")).setEphemeral(true).queue();
    }

    // ---- Helpers ----

    /** A Discord message with the given values markdown-escaped, except @mentions. */
    private String msg(String key, String... placeholders) {
        String[] escaped = placeholders.clone();
        for (int i = 1; i < escaped.length; i += 2) {
            if (!escaped[i].startsWith("<@")) {
                escaped[i] = MarkdownSanitizer.escape(escaped[i]);
            }
        }
        return truncate(plugin.messages().discord(key, escaped));
    }

    private static String limit(String description) {
        return truncate(description.isBlank() ? "Interaction" : description, 100);
    }

    private static String truncate(String text) {
        return truncate(text, Message.MAX_CONTENT_LENGTH);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
