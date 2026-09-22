package com.ultimakingdoms.factions;

import com.ultimakingdoms.api.McaCommunityRef;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface LegacyStandingProvider {
    LegacyStandingProvider UNAVAILABLE = Optional::empty;

    Optional<List<LegacyStanding>> standings();

    record LegacyStanding(UUID playerId, McaCommunityRef community, int score, long revision) { }
}
