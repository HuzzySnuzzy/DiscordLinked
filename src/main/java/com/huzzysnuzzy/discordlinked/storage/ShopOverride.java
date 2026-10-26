package com.huzzysnuzzy.discordlinked.storage;

/** The settings owner's change to one Shop item: turned off, and/or priced at {@code percent} of normal. */
public record ShopOverride(String item, boolean disabled, int percent) {
}
