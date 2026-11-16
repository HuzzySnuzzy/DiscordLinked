package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.GroupInvite;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.PlayerGroup;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * /dl: the player's own commands, on every server (they run on the proxy). /dl help lists everything, also the
 * commands DiscordLinkedBackend adds on the Minecraft servers (/sell, /home…).
 */
public final class PlayerCommands implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("help", "balance", "top", "leaderboard", "group", "homes",
            "spawn", "interactions", "shop", "website", "reminders", "setspawn");
    /** Who may use /dl setspawn, besides members with the Discord admin role. */
    public static final String SETSPAWN_PERMISSION = "discordlinked.setspawn";
    private static final List<String> GROUP_SUBCOMMANDS = List.of("info", "create", "invite", "accept", "decline",
            "leave", "name", "kick", "leader", "end");
    private static final List<String> ON_OFF = List.of("true", "false", "on", "off");

    private final DiscordLinked plugin;
    private final Community community;

    public PlayerCommands(DiscordLinked plugin) {
        this.plugin = plugin;
        this.community = plugin.community();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(plugin.messages().minecraft("minecraft.dl.player-only"));
            return;
        }
        String[] args = invocation.arguments();
        // Database work, so off the command thread.
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            try {
                run(player, args);
            } catch (SQLException e) {
                plugin.logger().error("Database error in /dl", e);
                say(player, "minecraft.command.database-error");
            }
        }).schedule();
    }

    private void run(Player player, String[] args) throws SQLException {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        String server = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
        switch (sub) {
            case "help", "?" -> help(player);
            case "balance", "credits", "bal" -> community.credits(player, rest.length == 0 ? "" : rest[0]);
            case "top" -> community.credits(player, "top");
            case "leaderboard", "lb", "sidebar" -> leaderboard(player, rest);
            case "group", "g" -> group(player, rest);
            case "homes" -> community.homes().list(player, server);
            case "spawn" -> community.homes().goSpawn(player, server);
            case "setspawn" -> {
                if (!isStaff(player)) {
                    say(player, "minecraft.spawn.no-permission");
                } else {
                    community.homes().setSpawn(player);
                }
            }
            case "interactions" -> {
                if (rest.length == 0 || !ON_OFF.contains(rest[0].toLowerCase(Locale.ROOT))) {
                    say(player, "minecraft.dl.usage", "usage", "/dl interactions on|off");
                } else {
                    community.toggle(player, isOn(rest[0]));
                }
            }
            case "shop" -> dla(player, "shop");
            case "website", "web" -> dla(player);
            case "reminders" -> dla(player, Stream.concat(Stream.of("reminders"), Arrays.stream(rest)).toArray(String[]::new));
            default -> {
                say(player, "minecraft.dl.unknown", "command", sub);
                help(player);
            }
        }
    }

    /** The same as /dla with these arguments. */
    private void dla(Player player, String... arguments) {
        new com.huzzysnuzzy.discordlinked.command.PanelCommand(plugin).execute(new Invocation() {
            public com.velocitypowered.api.command.CommandSource source() {
                return player;
            }

            public String[] arguments() {
                return arguments;
            }

            public String alias() {
                return "dla";
            }
        });
    }

    /** Staff: the Discord admin role (like the admin website), or the discordlinked.setspawn / admin permission. */
    private boolean isStaff(Player player) throws SQLException {
        if (player.hasPermission(SETSPAWN_PERMISSION)
                || player.hasPermission(com.huzzysnuzzy.discordlinked.command.AdminCommand.PERMISSION)) {
            return true;
        }
        Optional<LinkedAccount> account = plugin.database().findByUuid(player.getUniqueId());
        if (account.isEmpty() || !plugin.bot().isReady()) {
            return false;
        }
        var member = plugin.bot().checkMember(account.get().discordId());
        return member != null && member.admin();
    }

    private static boolean isOn(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return w.equals("true") || w.equals("on");
    }

    // ---- /dl help ----

    /** Every command a player can use, each one clickable (it's put in their chat box to finish). */
    private void help(Player player) {
        PluginConfig c = plugin.config();
        say(player, "minecraft.dl.help-header");
        line(player, "/dl balance [player]", "Your credits and microcredits, or someone else's");
        line(player, "/dl top", "The credits leaderboard in chat");
        line(player, "/dl leaderboard true|false", "The on-screen leaderboard: top credits and most active players");
        if (community.groups().open()) {
            line(player, "/dl group", "Your group: create, invite, accept, leave, name… (/dl group help)");
        }
        line(player, "/dl interactions on|off", "Let others pick you for /hug and friends, or not");
        line(player, "/dl spawn", "Go to the spawn (same as /spawn)");
        if (plugin.web() != null) {
            line(player, "/dl website", "A login link for the website (same as /dla)");
            line(player, "/dl shop", "The same, opening the Shop");
            line(player, "/dl reminders on|off", "In-game reminders that you have credits to spend");
        }
        say(player, "minecraft.dl.help-server");
        line(player, "/credits [player|top]", "Credits (also /mct for the microcredit leaderboard)");
        if (community.market().open()) {
            line(player, "/sell", "Sell items: put them in the menu and close it");
            line(player, "/sellhand [all]", "Sell what you hold (all: every one of it you have)");
            line(player, "/worth", "What the item you hold sells for");
            line(player, "/exchange <amount|all>", "Turn microcredits into credits");
        }
        if (community.homes().open()) {
            line(player, "/sethome [name]", "Save a home where you stand");
            line(player, "/home [name]", "Go to a home (Name:home for someone in your group)");
            line(player, "/homes", "Your homes, and your group's");
            line(player, "/delhome <name>", "Remove a home");
        }
        line(player, "/spawn", "Go to the spawn");
        if (plugin.config().fadEnabled() && plugin.servers().tpaAnywhere()) {
            line(player, "/tpa <player>", "Ask to teleport to a player");
            line(player, "/tpahere <player>", "Ask a player to teleport to you");
            line(player, "/tpaccept [player]", "Accept a teleport request (or click it)");
            line(player, "/tpdeny [player]", "Turn a teleport request down");
            line(player, "/tpacancel", "Take back the request you sent");
            line(player, "/tpatoggle", "Stop teleport requests to you (again: allow them)");
        }
        line(player, "/interactions", "The interactions: /hug, /kiss, /pet… <player>");
        try {
            if (isStaff(player)) {
                say(player, "minecraft.dl.help-staff");
                line(player, "/dl setspawn", "Make where you stand the spawn (for /spawn, on every server)");
            }
        } catch (SQLException e) {
            plugin.logger().warn("Could not check whether {} is staff", player.getUsername(), e);
        }
        if (!c.fadEnabled()) {
            say(player, "minecraft.fad.disabled");
        }
    }

    private void line(Player player, String command, String what) {
        // What's typed: the command's words up to its first argument ([optional], <needed> or one|other).
        StringBuilder words = new StringBuilder();
        boolean hasArgs = false;
        for (String word : command.split(" ")) {
            if (word.startsWith("[") || word.startsWith("<") || word.contains("|")) {
                hasArgs = true;
                break;
            }
            words.append(words.isEmpty() ? "" : " ").append(word);
        }
        String typed = words + (hasArgs ? " " : "");
        Component cmd = plugin.messages().minecraft("minecraft.dl.help-command", "command", command)
                .clickEvent(ClickEvent.suggestCommand(typed))
                .hoverEvent(HoverEvent.showText(plugin.messages().minecraft("minecraft.dl.help-hover", "command", typed.trim())));
        player.sendMessage(plugin.messages().minecraft("minecraft.dl.help-line", Placeholder.component("command", cmd),
                "what", what));
    }

    // ---- /dl leaderboard ----

    private void leaderboard(Player player, String[] rest) throws SQLException {
        Sidebar sidebar = community.sidebar();
        boolean show;
        if (rest.length == 0) {
            show = !sidebar.isOn(player.getUniqueId()); // on its own it switches it
        } else if (ON_OFF.contains(rest[0].toLowerCase(Locale.ROOT))) {
            show = isOn(rest[0]);
        } else {
            say(player, "minecraft.dl.usage", "usage", "/dl leaderboard true|false");
            return;
        }
        if (!plugin.config().fadEnabled()) {
            say(player, "minecraft.fad.disabled");
            return;
        }
        sidebar.set(player, show);
        say(player, show ? "minecraft.dl.leaderboard-on" : "minecraft.dl.leaderboard-off",
                "seconds", String.valueOf(plugin.config().sidebarSeconds()));
    }

    // ---- /dl group ----

    private void group(Player player, String[] rest) throws SQLException {
        Groups groups = community.groups();
        String sub = rest.length == 0 ? "info" : rest[0].toLowerCase(Locale.ROOT);
        String arg = rest.length <= 1 ? "" : String.join(" ", Arrays.copyOfRange(rest, 1, rest.length)).trim();
        if (sub.equals("help")) {
            groupHelp(player);
            return;
        }
        Optional<LinkedAccount> me = plugin.database().findByUuid(player.getUniqueId());
        if (me.isEmpty()) {
            say(player, "minecraft.dl.not-linked");
            return;
        }
        LinkedAccount account = me.get();
        Groups.Result result;
        switch (sub) {
            case "info" -> {
                info(player, account);
                return;
            }
            case "create" -> {
                if (arg.isEmpty()) {
                    say(player, "minecraft.dl.usage", "usage", "/dl group create <name>");
                    return;
                }
                result = groups.create(account, arg, null);
            }
            case "invite" -> {
                if (arg.isEmpty()) {
                    say(player, "minecraft.dl.usage", "usage", "/dl group invite <player>");
                    return;
                }
                result = groups.invite(account, arg, null);
            }
            case "accept", "decline" -> {
                List<GroupInvite> invites = plugin.database().groupInvites(account.uuid(), null);
                Optional<GroupInvite> invite = invites.stream()
                        .filter(i -> arg.isEmpty() || i.groupName().equalsIgnoreCase(arg)).findFirst();
                if (invites.isEmpty()) {
                    say(player, "minecraft.dl.group-no-invites");
                    return;
                }
                if (invite.isEmpty() || arg.isEmpty() && invites.size() > 1) {
                    say(player, "minecraft.dl.group-which-invite", "groups",
                            String.join(", ", invites.stream().map(GroupInvite::groupName).toList()), "command", sub);
                    return;
                }
                result = sub.equals("accept") ? groups.accept(account, invite.get().groupId(), null)
                        : groups.decline(account, invite.get().groupId());
            }
            case "leave" -> result = groups.leave(account, null);
            case "name", "rename" -> {
                if (arg.isEmpty()) {
                    say(player, "minecraft.dl.usage", "usage", "/dl group name <new name>");
                    return;
                }
                result = groups.rename(account, arg, null);
            }
            case "kick", "remove", "leader" -> {
                Optional<PlayerGroup.Member> member = groups.of(account.uuid()).flatMap(g -> g.members().stream()
                        .filter(m -> m.name() != null && m.name().equalsIgnoreCase(arg)).findFirst());
                if (arg.isEmpty()) {
                    say(player, "minecraft.dl.usage", "usage", "/dl group " + sub + " <player>");
                    return;
                }
                if (member.isEmpty()) {
                    say(player, "minecraft.dl.group-no-member", "player", arg);
                    return;
                }
                result = sub.equals("leader") ? groups.makeLeader(account, member.get().uuid(), null)
                        : groups.remove(account, member.get().uuid(), null);
            }
            case "end", "disband" -> result = groups.end(account, null);
            default -> {
                say(player, "minecraft.dl.unknown", "command", "group " + sub);
                groupHelp(player);
                return;
            }
        }
        say(player, result.ok() ? "minecraft.dl.done" : "minecraft.dl.failed", "message", result.message());
    }

    private void groupHelp(Player player) {
        say(player, "minecraft.dl.group-help-header");
        line(player, "/dl group", "Your group: its members and balance, or your invitations");
        line(player, "/dl group create <name>", "Make a group (you lead it)");
        line(player, "/dl group invite <player>", "Invite a linked player (leader)");
        line(player, "/dl group accept [group]", "Join a group that invited you");
        line(player, "/dl group decline [group]", "Say no to an invitation");
        line(player, "/dl group name <new name>", "Rename your group (leader)");
        line(player, "/dl group kick <player>", "Remove someone, with their share (leader)");
        line(player, "/dl group leader <player>", "Hand over leading the group (leader)");
        line(player, "/dl group leave", "Leave, with your share");
        line(player, "/dl group end", "End the group; everyone gets their share (leader)");
    }

    /** /dl group: the group's members and balance, or the player's invitations. */
    private void info(Player player, LinkedAccount me) throws SQLException {
        PluginConfig c = plugin.config();
        Optional<PlayerGroup> group = community.groups().of(me.uuid());
        if (group.isEmpty()) {
            List<GroupInvite> invites = plugin.database().groupInvites(me.uuid(), null);
            if (invites.isEmpty()) {
                say(player, "minecraft.dl.group-none");
            } else {
                for (GroupInvite invite : invites) {
                    Component accept = plugin.messages().minecraft("minecraft.dl.group-accept-link")
                            .clickEvent(ClickEvent.runCommand("/dl group accept " + invite.groupName()));
                    player.sendMessage(plugin.messages().minecraft("minecraft.dl.group-invite",
                            Placeholder.component("accept", accept), "group", invite.groupName(), "player",
                            invite.invitedBy() == null ? "?" : invite.invitedBy()));
                }
            }
            return;
        }
        PlayerGroup g = group.get();
        String leader = g.members().stream().filter(m -> m.uuid().equals(g.leader())).map(PlayerGroup.Member::name)
                .findFirst().orElse("?");
        say(player, "minecraft.dl.group-info", "group", g.name(), "leader", leader, "count", String.valueOf(g.members().size()),
                "balance", c.micro(g.balance()));
        List<String> names = new ArrayList<>();
        for (PlayerGroup.Member m : g.members()) {
            boolean online = plugin.proxy().getPlayer(m.uuid()).isPresent();
            names.add((online ? "● " : "○ ") + m.name() + (m.uuid().equals(g.leader()) ? " (leader)" : ""));
        }
        say(player, "minecraft.dl.group-members", "members", String.join(", ", names));
    }

    private void say(Player player, String key, String... placeholders) {
        if (!plugin.messages().isEmpty(key)) {
            player.sendMessage(plugin.messages().minecraft(key, placeholders));
        }
    }

    // ---- Tab completion ----

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return starting(SUBCOMMANDS.stream(), args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        if (args.length == 2) {
            switch (sub) {
                case "leaderboard", "lb", "sidebar", "interactions", "reminders" -> {
                    return starting(ON_OFF.stream(), last);
                }
                case "group", "g" -> {
                    return starting(GROUP_SUBCOMMANDS.stream(), last);
                }
                case "balance", "bal", "credits" -> {
                    return starting(plugin.proxy().getAllPlayers().stream().map(Player::getUsername), last);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (args.length == 3 && (sub.equals("group") || sub.equals("g"))) {
            String what = args[1].toLowerCase(Locale.ROOT);
            if (what.equals("invite") || what.equals("kick") || what.equals("leader")) {
                return starting(plugin.proxy().getAllPlayers().stream().map(Player::getUsername), last);
            }
        }
        return List.of();
    }

    private static List<String> starting(Stream<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).toList();
    }
}
