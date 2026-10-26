package com.huzzysnuzzy.discordlinked.fad;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The commands this plugin adds. They're registered from config.yml at startup, so their names can be changed
 * there. On Folia a player's command runs on the thread that owns their region.
 */
final class FadCommands {

    private FadCommands() {
    }

    /** One interaction, e.g. /kiss. Reads the action fresh each time so /discordlinkedfad reload applies. */
    static final class ActionCommand extends Command {

        private final DiscordLinkedFAD plugin;

        ActionCommand(DiscordLinkedFAD plugin, Action action) {
            super(action.name(), action.description(), ("/" + action.name() + " " + action.args()).trim(),
                    action.aliases());
            this.plugin = plugin;
            setPermission(action.permission());
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            FadSettings settings = plugin.settings();
            Action action = settings.actions.get(getName());
            if (action == null) {
                sender.sendMessage(settings.message("unavailable"));
                return true;
            }
            if (!(sender instanceof Player actor)) {
                sender.sendMessage(settings.message("player-only"));
                return true;
            }
            Player target = null;
            if (action.target() != Action.Target.NONE && args.length > 0) {
                target = plugin.findPlayer(actor, args[0]);
                if (target == null) {
                    actor.sendMessage(settings.message("not-found", Placeholder.unparsed("name", args[0])));
                    return true;
                }
                if (target.equals(actor) && !settings.allowSelf) {
                    actor.sendMessage(settings.message("self"));
                    return true;
                }
            } else if (action.target() == Action.Target.REQUIRED) {
                actor.sendMessage(settings.message("usage-target", Placeholder.unparsed("command", label)));
                return true;
            }
            long wait = plugin.startCooldown(actor);
            if (wait > 0) {
                actor.sendMessage(settings.message("cooldown", Placeholder.unparsed("seconds", String.valueOf(wait))));
                return true;
            }
            List<String> lines = action.lines(target != null);
            plugin.link().interact(actor, target, action, lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            Action action = plugin.settings().actions.get(getName());
            if (args.length != 1 || action == null || action.target() == Action.Target.NONE) {
                return List.of();
            }
            return plugin.playerNames(sender, args[0], plugin.settings().allowSelf);
        }
    }

    /** /credits, /credits <player>, /credits top. The proxy has the balances and answers in chat. */
    static final class CreditsCommand extends Command {

        private final DiscordLinkedFAD plugin;

        CreditsCommand(DiscordLinkedFAD plugin, FadSettings.CommandName name) {
            super(name.name(), "Show credits and the leaderboard", "/" + name.name() + " [player|top]", name.aliases());
            this.plugin = plugin;
            setPermission("discordlinkedfad.credits");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.settings().message("player-only"));
                return true;
            }
            plugin.link().credits(player, args.length == 0 ? "" : args[0]);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length != 1) {
                return List.of();
            }
            List<String> options = new ArrayList<>();
            if ("top".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                options.add("top");
            }
            options.addAll(plugin.playerNames(sender, args[0], true));
            return options;
        }
    }

    /** /interactions lists them; /interactions off|on opts the player out of (or back into) being picked. */
    static final class InteractionsCommand extends Command {

        private final DiscordLinkedFAD plugin;

        InteractionsCommand(DiscordLinkedFAD plugin, FadSettings.CommandName name) {
            super(name.name(), "List interactions, or turn them off or on for yourself",
                    "/" + name.name() + " [off|on]", name.aliases());
            this.plugin = plugin;
            setPermission("discordlinkedfad.interactions");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            String choice = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            Boolean enabled = switch (choice) {
                case "on", "enable", "true" -> true;
                case "off", "disable", "false" -> false;
                default -> null;
            };
            if (enabled == null) {
                list(sender, label);
            } else if (sender instanceof Player player) {
                plugin.link().toggle(player, enabled);
            } else {
                sender.sendMessage(plugin.settings().message("player-only"));
            }
            return true;
        }

        private void list(CommandSender sender, String label) {
            FadSettings settings = plugin.settings();
            List<Component> entries = new ArrayList<>();
            for (Action action : settings.actions.values()) {
                if (!sender.hasPermission(action.permission())) {
                    continue;
                }
                String command = "/" + action.name() + (action.target() == Action.Target.NONE ? "" : " ");
                entries.add(settings.message("list-entry",
                                Placeholder.unparsed("command", action.name()),
                                Placeholder.unparsed("args", action.args()),
                                Placeholder.unparsed("description", action.description()))
                        .clickEvent(ClickEvent.suggestCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text(action.description()))));
            }
            sender.sendMessage(settings.message("list-header"));
            sender.sendMessage(Component.join(JoinConfiguration.separator(settings.message("list-separator")), entries));
            sender.sendMessage(settings.message("list-footer", Placeholder.unparsed("command", label)));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length != 1) {
                return List.of();
            }
            return List.of("off", "on").stream().filter(o -> o.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
    }
}
