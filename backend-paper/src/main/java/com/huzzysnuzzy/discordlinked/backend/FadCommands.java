package com.huzzysnuzzy.discordlinked.backend;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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

    /** One interaction, e.g. /kiss. Reads the action fresh each time so /discordlinkedbackend reload applies. */
    static final class ActionCommand extends Command {

        private final DiscordLinkedBackend plugin;

        ActionCommand(DiscordLinkedBackend plugin, Action action) {
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

        private final DiscordLinkedBackend plugin;

        CreditsCommand(DiscordLinkedBackend plugin, FadSettings.CommandName name) {
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

        private final DiscordLinkedBackend plugin;

        InteractionsCommand(DiscordLinkedBackend plugin, FadSettings.CommandName name) {
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

    // ---- Selling for microcredits ----

    /** The checks every selling command shares. Returns the player, or null after saying why not. */
    private static Player seller(DiscordLinkedBackend plugin, Command command, CommandSender sender) {
        return seller(plugin, command, sender, true);
    }

    /**
     * Like {@link #seller(DiscordLinkedBackend, Command, CommandSender)}, but with {@code needOpen} false selling only
     * has to be supported by the proxy, not open here: /exchange is spending, so it works on servers where earning
     * microcredits is off (the proxy still says no while the whole market is closed).
     */
    private static Player seller(DiscordLinkedBackend plugin, Command command, CommandSender sender, boolean needOpen) {
        if (!command.testPermission(sender)) {
            return null;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.settings().message("player-only"));
            return null;
        }
        if (!plugin.sellPrices().known()) {
            player.sendMessage(plugin.settings().message("sell-unsupported"));
            return null;
        }
        if (needOpen && !plugin.sellPrices().available()) {
            player.sendMessage(plugin.settings().message("sell-closed"));
            return null;
        }
        return player;
    }

    private static Component itemName(org.bukkit.Material material) {
        return Component.translatable(material.translationKey());
    }

    /** /sell: opens the sell menu; closing it sells. */
    static final class SellCommand extends Command {

        private final DiscordLinkedBackend plugin;

        SellCommand(DiscordLinkedBackend plugin, FadSettings.CommandName name) {
            super(name.name(), "Sell items for microcredits: put them in the chest and close it", "/" + name.name(),
                    name.aliases());
            this.plugin = plugin;
            setPermission("discordlinkedfad.sell");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            Player player = seller(plugin, this, sender);
            if (player != null) {
                SellMenu.open(plugin, player);
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return List.of();
        }
    }

    /** /sellhand sells what's in the main hand; /sellhand all also every matching stack in the inventory. */
    static final class SellHandCommand extends Command {

        private final DiscordLinkedBackend plugin;

        SellHandCommand(DiscordLinkedBackend plugin, FadSettings.CommandName name) {
            super(name.name(), "Sell what you're holding, or with \"all\" every one of it you have",
                    "/" + name.name() + " [all]", name.aliases());
            this.plugin = plugin;
            setPermission("discordlinkedfad.sell");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            Player player = seller(plugin, this, sender);
            if (player == null) {
                return true;
            }
            FadSettings settings = plugin.settings();
            org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
            ItemStack hand = inventory.getItemInMainHand();
            if (hand.getType().isAir()) {
                player.sendMessage(settings.message("sell-empty-hand"));
                return true;
            }
            if (!checkSellable(plugin, player, hand)) {
                return true;
            }
            List<ItemStack> taken = new ArrayList<>();
            if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
                ItemStack like = hand.clone();
                // The main inventory and hotbar; never armour or the off hand.
                for (int slot = 0; slot < 36; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (stack != null && stack.isSimilar(like)) {
                        taken.add(stack.clone());
                        inventory.setItem(slot, null);
                    }
                }
            } else {
                taken.add(hand.clone());
                inventory.setItemInMainHand(null);
            }
            plugin.sell(player, taken);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 && "all".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("all") : List.of();
        }
    }

    /** Tells the player why an item can't be sold; true if it can. */
    private static boolean checkSellable(DiscordLinkedBackend plugin, Player player, ItemStack stack) {
        SellPrices prices = plugin.sellPrices();
        if (prices.price(stack.getType()) == null) {
            player.sendMessage(plugin.settings().message("sell-unsellable",
                    Placeholder.component("item", itemName(stack.getType()))));
            return false;
        }
        if (!SellPrices.isPlain(stack)) {
            player.sendMessage(plugin.settings().message("sell-special"));
            return false;
        }
        return true;
    }

    /** /worth: what the held item sells for. */
    static final class WorthCommand extends Command {

        private final DiscordLinkedBackend plugin;

        WorthCommand(DiscordLinkedBackend plugin, FadSettings.CommandName name) {
            super(name.name(), "Show what the item you're holding sells for", "/" + name.name(), name.aliases());
            this.plugin = plugin;
            setPermission("discordlinkedfad.sell");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            Player player = seller(plugin, this, sender);
            if (player == null) {
                return true;
            }
            FadSettings settings = plugin.settings();
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                player.sendMessage(settings.message("sell-empty-hand"));
                return true;
            }
            SellPrices prices = plugin.sellPrices();
            SellPrices.Price price = prices.price(hand.getType());
            if (price == null) {
                player.sendMessage(settings.message("sell-unsellable", Placeholder.component("item", itemName(hand.getType()))));
                return true;
            }
            int size = hand.getType().getMaxStackSize();
            player.sendMessage(settings.message("worth",
                    Placeholder.component("item", itemName(hand.getType())),
                    Placeholder.unparsed("each", prices.format(price.now())),
                    Placeholder.unparsed("stack", prices.format(price.now() * size)),
                    Placeholder.unparsed("size", String.valueOf(size))));
            if (price.normal() > 0 && price.now() * 100 / price.normal() < 95) {
                player.sendMessage(settings.message("worth-dropped",
                        Placeholder.unparsed("percent", String.valueOf(Math.round(price.now() * 100.0 / price.normal())))));
            }
            if (!SellPrices.isPlain(hand)) {
                player.sendMessage(settings.message("sell-special"));
            } else if (hand.getAmount() > 1) {
                player.sendMessage(settings.message("worth-holding",
                        Placeholder.unparsed("amount", String.valueOf(hand.getAmount())),
                        Placeholder.unparsed("total", prices.formatTotal(price.now() * hand.getAmount()))));
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return List.of();
        }
    }

    /** /mct: the microcredit leaderboard. The proxy answers in chat. */
    static final class MicroTopCommand extends Command {

        private final DiscordLinkedBackend plugin;

        MicroTopCommand(DiscordLinkedBackend plugin, FadSettings.CommandName name) {
            super(name.name(), "Who has the most microcredits", "/" + name.name(), name.aliases());
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
            plugin.link().microTop(player);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return List.of();
        }
    }

    /** /exchange <amount|all>: microcredits into credits. The proxy answers in chat. */
    static final class ExchangeCommand extends Command {

        private final DiscordLinkedBackend plugin;

        ExchangeCommand(DiscordLinkedBackend plugin, FadSettings.CommandName name) {
            super(name.name(), "Exchange microcredits for credits", "/" + name.name() + " <amount|all>", name.aliases());
            this.plugin = plugin;
            setPermission("discordlinkedfad.sell");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            Player player = seller(plugin, this, sender, false);
            if (player == null) {
                return true;
            }
            plugin.link().exchange(player, args.length == 0 ? "" : args[0]);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 && "all".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("all") : List.of();
        }
    }
}
