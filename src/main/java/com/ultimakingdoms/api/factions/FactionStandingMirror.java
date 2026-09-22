package com.ultimakingdoms.api.factions;

@FunctionalInterface
public interface FactionStandingMirror {
    void standingChanged(FactionStandingResult result, FactionStandingRequest request);
}
