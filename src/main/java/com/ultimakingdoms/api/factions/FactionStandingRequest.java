package com.ultimakingdoms.api.factions;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record FactionStandingRequest(UUID playerId, ResourceLocation kingdomId, int delta,
                                     ResourceLocation source, FactionChangeCause cause,
                                     UUID correlationId, long sourceRevision,
                                     Optional<UUID> settlementId, Optional<String> description,
                                     boolean quiet) {
    public FactionStandingRequest {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(kingdomId, "kingdomId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(correlationId, "correlationId");
        if (sourceRevision < 0) throw new IllegalArgumentException("sourceRevision must be non-negative");
        settlementId = settlementId == null ? Optional.empty() : settlementId;
        description = description == null ? Optional.empty() : description
                .map(String::strip).filter(value -> !value.isEmpty());
    }
}
