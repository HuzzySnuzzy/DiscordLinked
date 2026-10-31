package com.huzzysnuzzy.discordlinked.backend;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * The sound, particles and leap of an interaction. Each player is only touched from their own scheduler, which
 * on Folia is the thread that owns their region (the two players may be in different regions).
 */
final class Effects {

    private static final double MAX_LEAP_DISTANCE = 12;

    private Effects() {
    }

    /** Plays at the target if there is one, otherwise at the actor. */
    static void play(Plugin plugin, Action action, Player actor, Player target) {
        if (target == null) {
            actor.getScheduler().run(plugin, task -> at(action, actor), null);
            return;
        }
        target.getScheduler().run(plugin, task -> {
            at(action, target);
            if (action.leap()) {
                Location goal = target.getLocation();
                actor.getScheduler().run(plugin, leapTask -> leap(actor, goal), null);
            }
        }, null);
    }

    private static void at(Action action, Player player) {
        Location location = player.getLocation();
        if (action.sound() != null) {
            // By key rather than the Sound enum, so new and renamed sounds work on any version.
            location.getWorld().playSound(location, action.sound(), SoundCategory.PLAYERS, 1f, 1f);
        }
        if (action.particle() != null) {
            location.getWorld().spawnParticle(action.particle(), location.add(0, player.getHeight() + 0.3, 0),
                    7, 0.35, 0.2, 0.35, 0.02);
        }
    }

    /** A playful hop toward the target; just a hop up when they're far away or in another world. */
    private static void leap(Player actor, Location goal) {
        Location from = actor.getLocation();
        Vector toward = goal.toVector().subtract(from.toVector()).setY(0);
        double distance = toward.length();
        if (!from.getWorld().equals(goal.getWorld()) || distance < 0.5 || distance > MAX_LEAP_DISTANCE) {
            actor.setVelocity(actor.getVelocity().setY(0.42));
            return;
        }
        actor.setVelocity(toward.normalize().multiply(Math.min(1.1, 0.3 + distance * 0.11)).setY(0.42));
    }
}
