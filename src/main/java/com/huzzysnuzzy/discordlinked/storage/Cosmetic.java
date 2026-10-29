package com.huzzysnuzzy.discordlinked.storage;

/**
 * Something players buy once with credits in the website's Cosmetics shop. {@code kind} is prefix (value: the
 * prefix text with &amp; colour codes), trail (value: a Minecraft particle), card (value: the profile card image's
 * file name), aura (particles while standing still; value: a Minecraft particle) or keepinv (no value).
 * {@code style} is how a trail or aura looks by default ("shape;animation;colour", empty = the kind's default).
 * {@code forSale} false keeps it out of the shop; the owner can still give it.
 */
public record Cosmetic(long id, String kind, String name, String value, String style, long price, boolean forSale,
                       int sort) {
}
