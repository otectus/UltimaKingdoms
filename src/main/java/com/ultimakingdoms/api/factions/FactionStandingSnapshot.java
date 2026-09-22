package com.ultimakingdoms.api.factions;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record FactionStandingSnapshot(UUID playerId, ResourceLocation kingdomId, int score,
                                      ResourceLocation ladderId, String tierId,
                                      String highWaterTierId, long revision) {
    public FactionStandingSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(kingdomId, "kingdomId");
        Objects.requireNonNull(ladderId, "ladderId");
        Objects.requireNonNull(tierId, "tierId");
        Objects.requireNonNull(highWaterTierId, "highWaterTierId");
    }
}
