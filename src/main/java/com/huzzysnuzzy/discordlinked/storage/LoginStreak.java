package com.huzzysnuzzy.discordlinked.storage;

import java.time.LocalDate;

/**
 * A player's daily login streak. {@code current} is the streak as of {@code lastDay}; it's still alive today if
 * they joined yesterday or today. {@code newDay} is set when this join was their first of the day.
 */
public record LoginStreak(int current, int best, LocalDate lastDay, boolean newDay) {

    /** The streak as it stands on {@code today}: 0 once a day has been missed. */
    public int currentOn(LocalDate today) {
        return lastDay != null && !lastDay.isBefore(today.minusDays(1)) ? current : 0;
    }
}
