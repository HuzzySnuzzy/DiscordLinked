package com.huzzysnuzzy.discordlinked.storage;

import java.util.UUID;

/** Any player the plugin has seen, linked or not. {@code discordId} is null when not linked. */
public record PlayerRef(UUID uuid, String name, Long discordId) {

    public static PlayerRef of(LinkedAccount account) {
        return new PlayerRef(account.uuid(), account.minecraftName(), account.discordId());
    }
}
