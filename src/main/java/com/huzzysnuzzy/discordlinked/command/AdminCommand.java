package com.huzzysnuzzy.discordlinked.command;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.servers.BackendServers;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * /dlink lookup|unlink &lt;player&gt; and /dlink maintenance [server] [on|off] — in-game admin tools.
 */
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
        if (args.length >= 1 && args[0].equalsIgnoreCase("maintenance")) {
            plugin.proxy().getScheduler().buildTask(plugin, () -> maintenance(source, args)).schedule();
            return;
        }
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
                plugin.unlink(a, by + " (in-game)", "/dlink unlink", true);
                source.sendMessage(plugin.messages().minecraft("minecraft.command.unlinked", "player", a.minecraftName()));
            }
        } catch (SQLException e) {
            plugin.logger().error("Database error in /dlink", e);
            source.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
        }
    }

    /** /dlink maintenance: which servers are in maintenance; with a server, its state; with on|off, switches it. */
    private void maintenance(CommandSource source, String[] args) {
        BackendServers servers = plugin.servers();
        if (args.length == 1) {
            List<String> names = servers.inMaintenance();
            source.sendMessage(names.isEmpty() ? plugin.messages().minecraft("minecraft.command.maintenance-none")
                    : plugin.messages().minecraft("minecraft.command.maintenance-list", "servers", String.join(", ", names)));
            return;
        }
        String state = args.length == 3 ? args[2].toLowerCase(Locale.ROOT) : null;
        if (args.length > 3 || (state != null && !state.equals("on") && !state.equals("off"))) {
            source.sendMessage(plugin.messages().minecraft("minecraft.command.maintenance-usage"));
            return;
        }
        String name = servers.name(args[1]);
        if (name == null) {
            source.sendMessage(plugin.messages().minecraft("minecraft.command.unknown-server", "server", args[1]));
            return;
        }
        if (state == null) {
            source.sendMessage(plugin.messages().minecraft("minecraft.command.maintenance-state", "server", name,
                    "state", servers.inMaintenance(name) ? "on" : "off"));
            return;
        }
        String by = source instanceof Player p ? p.getUsername() : "console";
        try {
            servers.setMaintenance(name, state.equals("on"), null, by + " (in-game)");
        } catch (SQLException e) {
            plugin.logger().error("Database error in /dlink maintenance", e);
            source.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
            return;
        }
        source.sendMessage(plugin.messages().minecraft("minecraft.command.maintenance-set", "server", name,
                "state", state));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return Stream.of("lookup", "unlink", "maintenance").filter(s -> s.startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("maintenance")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            Stream<String> options = args.length == 2 ? plugin.servers().names().stream()
                    : args.length == 3 ? Stream.of("on", "off") : Stream.empty();
            return options.filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
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
