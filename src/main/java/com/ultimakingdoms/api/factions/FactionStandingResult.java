package com.ultimakingdoms.api.factions;

import java.util.Objects;

public record FactionStandingResult(Status status, int requestedDelta, int appliedDelta,
                                    FactionStandingSnapshot standing) {
    public FactionStandingResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(standing, "standing");
    }

    public boolean applied() {
        return status == Status.APPLIED;
    }

    public enum Status {
        APPLIED,
        REPLAYED,
        STALE_SOURCE_REVISION,
        READ_ONLY
    }
}
