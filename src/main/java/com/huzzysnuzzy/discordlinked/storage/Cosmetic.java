package com.huzzysnuzzy.discordlinked.storage;

/**
 * Something players buy once with credits in the website's Cosmetics shop. {@code kind} is prefix (value: the
 * prefix text with &amp; colour codes), trail (value: a Minecraft particle), card (value: the profile card image's
 * file name) or keepinv (no value). {@code forSale} false keeps it out of the shop; the owner can still give it.
 */
public record Cosmetic(long id, String kind, String name, String value, long price, boolean forSale, int sort) {
}
