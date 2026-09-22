package com.ultimakingdoms.factions;

import com.ultimakingdoms.api.McaCommunityRef;
import com.ultimakingdoms.api.factions.LocalStandingEffectResult;

import java.util.UUID;

@FunctionalInterface
public interface LocalEffectProvider {
    LocalEffectProvider UNAVAILABLE = (player, community, delta, correlation, revision, description) ->
            LocalStandingEffectResult.UNAVAILABLE;

    LocalStandingEffectResult deliver(UUID playerId, McaCommunityRef community, int delta,
                                      UUID correlationId, long sourceRevision, String description);
}
