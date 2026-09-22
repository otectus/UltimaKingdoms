package com.ultimakingdoms.api.townstead;

/** Independently probed Townstead integration capabilities. */
public enum TownsteadCapability {
    READ_VILLAGER,
    READ_NEEDS,
    READ_SCHEDULE,
    READ_CALENDAR,
    READ_BUILDING,
    READ_BUILDINGS,
    READ_ORIGIN,
    READ_GENE,
    READ_SPIRIT,
    DISPATCH_REACTION;

    public String serializedName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
