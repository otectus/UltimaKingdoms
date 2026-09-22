package com.ultimakingdoms.api.factions;

public record EffectiveStanding(boolean localAvailable, int localScore, int factionScore,
                                int factionModifier, int effectiveScore) {
}
