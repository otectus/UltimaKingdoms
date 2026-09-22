package com.ultimakingdoms.api.townstead;

import java.util.List;
import java.util.Objects;

public record TownsteadScheduleView(
        String mode, String templateId, boolean customShifts, boolean nonDefaultCustomShifts,
        int currentTickHour, int currentDisplayHour, int currentShiftOrdinal,
        String currentActivity, String plannedActivity, String currentTemplateId,
        List<Integer> shifts, List<String> weekDayTemplates
) {
    public TownsteadScheduleView {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(currentActivity, "currentActivity");
        Objects.requireNonNull(plannedActivity, "plannedActivity");
        Objects.requireNonNull(currentTemplateId, "currentTemplateId");
        shifts = List.copyOf(Objects.requireNonNull(shifts, "shifts"));
        weekDayTemplates = List.copyOf(Objects.requireNonNull(weekDayTemplates, "weekDayTemplates"));
    }
}
