package com.huzzysnuzzy.discordlinked.fad;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PrefixNode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bought prefixes as LuckPerms prefixes, so every chat and tab plugin that shows LuckPerms prefixes shows them.
 * They're transient: never saved to LuckPerms' storage, only kept while the player is on this server. Only loaded
 * when LuckPerms is installed.
 */
final class LuckPermsPrefixes {

    private final LuckPerms luckPerms = LuckPermsProvider.get();
    /** The node each player got from us, so it can be swapped or taken off again. */
    private final Map<UUID, Node> applied = new ConcurrentHashMap<>();

    /** Sets (or with an empty text, removes) a player's bought prefix. False if LuckPerms hasn't loaded them. */
    boolean set(UUID player, String prefix, int priority) {
        User user = luckPerms.getUserManager().getUser(player);
        if (user == null) {
            return false;
        }
        Node old = applied.remove(player);
        if (old != null) {
            user.transientData().remove(old);
        }
        if (!prefix.isEmpty()) {
            Node node = PrefixNode.builder(prefix, priority).build();
            user.transientData().add(node);
            applied.put(player, node);
        }
        return true;
    }

    /** The player left; LuckPerms drops transient data with them. */
    void forget(UUID player) {
        applied.remove(player);
    }
}
