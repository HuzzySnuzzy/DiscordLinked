package com.huzzysnuzzy.discordlinked.fad;

import org.bukkit.Particle;

import java.util.List;

/**
 * One interaction from config.yml, e.g. /kiss. {@code sound} and {@code particle} may be null.
 */
record Action(String name, String description, List<String> aliases, Target target,
              List<String> messages, List<String> soloMessages, String sound, Particle particle, boolean leap) {

    enum Target { REQUIRED, OPTIONAL, NONE }

    /** The lines to pick from, falling back to the other list when this case has none. */
    List<String> lines(boolean withTarget) {
        List<String> preferred = withTarget ? messages : soloMessages;
        return preferred.isEmpty() ? (withTarget ? soloMessages : messages) : preferred;
    }

    /** "<player>", "[player]" or "" for the usage help. */
    String args() {
        return switch (target) {
            case REQUIRED -> "<player>";
            case OPTIONAL -> "[player]";
            case NONE -> "";
        };
    }

    String permission() {
        return "discordlinkedfad.interact." + name;
    }
}
