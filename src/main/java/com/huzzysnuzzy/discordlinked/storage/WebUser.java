package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;

/** Someone who logged in to the website with their Discord account, linked to Minecraft or not. */
public record WebUser(long discordId, String name, String avatar, Instant firstLogin, Instant lastLogin) {
}
