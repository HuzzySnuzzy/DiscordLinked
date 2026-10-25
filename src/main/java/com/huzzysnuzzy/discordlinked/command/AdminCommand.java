package com.huzzysnuzzy.discordlinked.command;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** /dlink lookup|unlink &lt;player&gt; — in-game admin tools. */
public final class AdminCommand implements SimpleCommand {

    public static final String PERMISSION = "discordlinked.admin";

    private final DiscordLinked plugin;

    public AdminCommand(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length != 2 || !List.of("lookup", "unlink").contains(args[0].toLowerCase())) {
            source.sendMessage(Component.text("Usage: /dlink <lookup|unlink> <player>", NamedTextColor.RED));
            return;
        }
        plugin.proxy().getScheduler().buildTask(plugin, () -> run(source, args[0].toLowerCase(), args[1])).schedule();
    }

    private void run(CommandSource source, String action, String name) {
        try {
            Optional<LinkedAccount> account = plugin.database().findByName(name);
            if (account.isEmpty()) {
                source.sendMessage(Component.text(name + " is not linked.", NamedTextColor.YELLOW));
                return;
            }
            LinkedAccount a = account.get();
            if (action.equals("lookup")) {
                source.sendMessage(Component.text(a.minecraftName() + " (" + a.uuid() + ")", NamedTextColor.GOLD));
                source.sendMessage(Component.text("Discord ID: " + a.discordId() + ", linked " + a.linkedAt(),
                        NamedTextColor.GRAY));
            } else {
                String by = source instanceof Player p ? p.getUsername() : "console";
                plugin.unlink(a, "admin " + by + " (in-game)");
                source.sendMessage(Component.text("Unlinked " + a.minecraftName() + ".", NamedTextColor.GREEN));
            }
        } catch (SQLException e) {
            plugin.logger().error("Database error in /dlink", e);
            source.sendMessage(Component.text("Database error, check the console.", NamedTextColor.RED));
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
