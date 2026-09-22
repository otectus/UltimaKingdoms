package com.ultimakingdoms.api.townstead;

import java.util.Objects;

public record TownsteadCalendarView(
        String profileId, long worldDay, int epochYearOffset, String timeMode,
        int year, int month, int day, int dayOfYear, int dayOfWeek, String season
) {
    public TownsteadCalendarView {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(timeMode, "timeMode");
        Objects.requireNonNull(season, "season");
    }
}
