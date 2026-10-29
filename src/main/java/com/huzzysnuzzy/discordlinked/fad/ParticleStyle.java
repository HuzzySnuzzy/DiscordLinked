package com.huzzysnuzzy.discordlinked.fad;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a trail or standing-still particle effect looks: its shape, how it moves and an optional colour. Stored as
 * {@code shape;animation;colour} (colour empty = the particle's own, {@code rainbow}, or {@code #rrggbb}).
 * DiscordLinkedFAD draws it. Only particles Minecraft can colour ({@link Cosmetics#COLORABLE}) use the colour.
 */
public record ParticleStyle(String shape, String animation, String color) {

    public static final List<String> SHAPES = List.of("cloud", "ring", "halo", "orbit", "helix", "wings");
    public static final List<String> ANIMATIONS = List.of("still", "spin", "pulse", "rise");
    public static final String RAINBOW = "rainbow";

    static final ParticleStyle TRAIL_DEFAULT = new ParticleStyle("cloud", "still", "");
    static final ParticleStyle AURA_DEFAULT = new ParticleStyle("orbit", "spin", "");

    private static final Pattern HEX = Pattern.compile("#[0-9a-f]{6}");

    /** The default look for a kind of particle cosmetic. */
    static ParticleStyle defaultFor(String kind) {
        return Cosmetics.AURA.equals(kind) ? AURA_DEFAULT : TRAIL_DEFAULT;
    }

    /** Reads a stored style; anything missing or unknown falls back to {@code fallback}'s part. */
    static ParticleStyle parse(String text, ParticleStyle fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String[] parts = text.split(";", -1);
        String shape = parts.length > 0 && SHAPES.contains(parts[0]) ? parts[0] : fallback.shape();
        String animation = parts.length > 1 && ANIMATIONS.contains(parts[1]) ? parts[1] : fallback.animation();
        String color = parts.length > 2 && validColor(parts[2]) ? parts[2] : fallback.color();
        return new ParticleStyle(shape, animation, color);
    }

    /** A style from the website. Throws IllegalArgumentException saying what's wrong. */
    public static ParticleStyle of(String shape, String animation, String color) {
        if (!SHAPES.contains(shape)) {
            throw new IllegalArgumentException("Pick a shape.");
        }
        if (!ANIMATIONS.contains(animation)) {
            throw new IllegalArgumentException("Pick an animation.");
        }
        String c = color == null ? "" : color.strip().toLowerCase(Locale.ROOT);
        if (!validColor(c)) {
            throw new IllegalArgumentException("Pick a colour.");
        }
        return new ParticleStyle(shape, animation, c);
    }

    private static boolean validColor(String color) {
        return color.isEmpty() || color.equals(RAINBOW) || HEX.matcher(color).matches();
    }

    public String encode() {
        return shape + ";" + animation + ";" + color;
    }
}
