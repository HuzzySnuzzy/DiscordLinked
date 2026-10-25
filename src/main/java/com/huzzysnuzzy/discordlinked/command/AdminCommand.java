package com.huzzysnuzzy.discordlinked.command;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** /dlink lookup|unlink &lt;player&gt; — in-game admin tools. */
public final class AdminCommand implements SimpleCommand {

    public static final String PERMISSION = "discordlinked.admin";
    private static final DateTimeFormatter LINKED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final DiscordLinked plugin;

    public AdminCommand(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length != 2 || !List.of("lookup", "unlink").contains(args[0].toLowerCase())) {
            source.sendMessage(plugin.messages().minecraft("minecraft.command.usage"));
            return;
        }
        plugin.proxy().getScheduler().buildTask(plugin, () -> run(source, args[0].toLowerCase(), args[1])).schedule();
    }

    private void run(CommandSource source, String action, String name) {
        try {
            Optional<LinkedAccount> account = plugin.database().findByName(name);
            if (account.isEmpty()) {
                source.sendMessage(plugin.messages().minecraft("minecraft.command.not-linked", "player", name));
                return;
            }
            LinkedAccount a = account.get();
            if (action.equals("lookup")) {
                source.sendMessage(plugin.messages().minecraft("minecraft.command.lookup",
                        "player", a.minecraftName(),
                        "uuid", a.uuid().toString(),
                        "discord_id", String.valueOf(a.discordId()),
                        "linked_at", LINKED_AT_FORMAT.format(a.linkedAt())));
            } else {
                String by = source instanceof Player p ? p.getUsername() : "console";
                plugin.unlink(a, "admin " + by + " (in-game)");
                source.sendMessage(plugin.messages().minecraft("minecraft.command.unlinked", "player", a.minecraftName()));
            }
        } catch (SQLException e) {
            plugin.logger().error("Database error in /dlink", e);
            source.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return List.of("lookup", "unlink");
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return plugin.proxy().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
