package com.huzzysnuzzy.discordlinked.fad;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where the particles of a trail or standing-still effect go, as offsets from the player's feet. Pure maths (no
 * Minecraft), so the shapes and animations are easy to check.
 *
 * <p>Shapes: cloud (a puff), ring (around the feet), halo (above the head), orbit (around the waist), helix (two
 * strands spiralling up the body) and wings (behind the back). Animations: still, spin, pulse (grows and shrinks;
 * wings flap) and rise (moves up the body, or bobs).
 */
final class ParticleShapes {

    /** One particle: where it goes (offset from the feet) and which one of the shape it is (for rainbow colours). */
    record Point(double x, double y, double z, int index) {
    }

    /** How a style is drawn. Unknown parts fall back to a cloud that stands still. */
    record Style(String shape, String animation, String color) {
        static final Style NONE = new Style("cloud", "still", "");

        /** Reads "shape;animation;colour" as the proxy sends it. */
        static Style parse(String text, Style fallback) {
            if (text == null || text.isBlank()) {
                return fallback;
            }
            String[] parts = text.split(";", -1);
            return new Style(parts[0].isEmpty() ? fallback.shape() : parts[0],
                    parts.length > 1 && !parts[1].isEmpty() ? parts[1] : fallback.animation(),
                    parts.length > 2 ? parts[2] : fallback.color());
        }
    }

    private static final double TAU = Math.PI * 2;

    private ParticleShapes() {
    }

    /**
     * The particles to show now.
     *
     * @param tick    ticks since the effect started (drives the animation)
     * @param bodyYaw the way the player's body faces, in degrees (Minecraft's yaw; only wings use it)
     * @param amount  how many particles a cloud has
     */
    static List<Point> points(Style style, long tick, float bodyYaw, int amount) {
        List<Point> points = new ArrayList<>();
        String animation = style.animation();
        double pulse = animation.equals("pulse") ? 1 + 0.3 * Math.sin(tick * 0.25) : 1;
        double spin = animation.equals("spin") ? tick * 0.22 : 0;
        // 0..1 over about two seconds: how far up the body a rising shape is.
        double up = (tick % 40) / 40.0;
        double bob = animation.equals("rise") ? 0.12 * Math.sin(tick * 0.2) : 0;
        switch (style.shape()) {
            case "ring" -> circle(points, animation, animation.equals("rise") ? 0.1 + up * 1.9 : 0.1, 0.6 * pulse, spin, 16);
            case "halo" -> circle(points, animation, 2.15 + bob, 0.32 * pulse, spin, 12);
            case "orbit" -> circle(points, animation,
                    animation.equals("rise") ? 1.0 + 0.8 * Math.sin(tick * 0.12) : 1.0, 0.75 * pulse, spin, 10);
            case "helix" -> helix(points, animation, tick, pulse, spin, up);
            case "wings" -> wings(points, animation, tick, bodyYaw, bob);
            default -> cloud(points, animation, amount, up, bob);
        }
        return points;
    }

    /** A circle at a height: whole (still, pulse, rise) or three points running round it (spin). */
    private static void circle(List<Point> points, String animation, double y, double radius, double spin, int count) {
        int shown = animation.equals("spin") ? 3 : count;
        for (int i = 0; i < shown; i++) {
            double angle = spin + TAU * i / shown;
            points.add(new Point(Math.cos(angle) * radius, y, Math.sin(angle) * radius, i));
        }
    }

    /** Two strands spiralling up the body; rising sends a pair of points up them instead. */
    private static void helix(List<Point> points, String animation, long tick, double pulse, double spin, double up) {
        double radius = 0.5 * pulse;
        if (animation.equals("rise")) {
            double y = up * 2.0;
            for (int strand = 0; strand < 2; strand++) {
                double angle = y * Math.PI * 2 + strand * Math.PI;
                points.add(new Point(Math.cos(angle) * radius, y, Math.sin(angle) * radius, strand));
            }
            return;
        }
        int steps = 10;
        for (int strand = 0; strand < 2; strand++) {
            for (int i = 0; i < steps; i++) {
                double y = 2.0 * i / (steps - 1);
                double angle = y * Math.PI * 1.5 + strand * Math.PI + spin;
                points.add(new Point(Math.cos(angle) * radius, y, Math.sin(angle) * radius, strand * steps + i));
            }
        }
    }

    /**
     * Two wings behind the back, facing the way the body faces. Pulse and spin make them flap (spin faster),
     * rise makes them bob.
     */
    private static void wings(List<Point> points, String animation, long tick, float bodyYaw, double bob) {
        double flap = switch (animation) {
            case "pulse" -> Math.sin(tick * 0.2);
            case "spin" -> Math.sin(tick * 0.45);
            default -> 0;
        };
        double open = Math.toRadians(25 + 20 * flap); // how far the wings fold back
        double yaw = Math.toRadians(bodyYaw);
        // Minecraft: yaw 0 faces +z; forward = (-sin, cos), the player's right = (-cos, -sin).
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        int index = 0;
        for (int side = -1; side <= 1; side += 2) {
            for (double[] p : WING) {
                double u = p[0];
                double v = p[1] + bob;
                double sideways = side * u * Math.cos(open);
                double back = 0.25 + u * Math.sin(open);
                points.add(new Point(rx * sideways - fx * back, v, rz * sideways - fz * back, index++));
            }
        }
    }

    /** One wing's outline: distance out from the spine and height, from the shoulder to the tip and back. */
    private static final double[][] WING = wingOutline();

    private static double[][] wingOutline() {
        List<double[]> outline = new ArrayList<>();
        int steps = 7;
        for (int i = 0; i <= steps; i++) { // top edge, curving up to the tip
            double t = (double) i / steps;
            outline.add(new double[]{0.15 + t * 0.85, 1.35 + 0.55 * Math.sin(t * Math.PI * 0.85)});
        }
        for (int i = steps - 1; i >= 1; i--) { // bottom edge, back down to the waist
            double t = (double) i / steps;
            outline.add(new double[]{0.15 + t * 0.85, 0.95 + 0.6 * t * t});
        }
        return outline.toArray(new double[0][]);
    }

    /** A loose puff around the feet, or the whole body when rising. */
    private static void cloud(List<Point> points, String animation, int amount, double up, double bob) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double y = animation.equals("rise") ? up * 1.8 : 0.15 + Math.max(0, bob);
        double spread = animation.equals("pulse") ? 0.15 + 0.2 * Math.abs(Math.sin(up * TAU)) : 0.2;
        for (int i = 0; i < amount; i++) {
            points.add(new Point(random.nextGaussian() * spread, y + random.nextGaussian() * 0.05,
                    random.nextGaussian() * spread, i));
        }
    }
}
