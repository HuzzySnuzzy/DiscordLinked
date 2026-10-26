package com.huzzysnuzzy.discordlinked.storage;

/**
 * The settings owner's change to one item: not for sale in the Shop and/or priced at {@code percent} of normal;
 * can't be sold in game and/or sells for {@code sellPercent} of its normal sell price.
 */
public record ShopOverride(String item, boolean disabled, int percent, boolean sellDisabled, int sellPercent) {
}
