package com.ultimakingdoms.api.gating;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** An immutable, side-effect-free snapshot used for one kingdom-gate evaluation. */
public record KingdomContext(
        UUID settlementId,
        ResourceLocation kingdomId,
        ResourceLocation dimension,
        long settlementRevision
) {
    public KingdomContext {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(kingdomId, "kingdomId");
        Objects.requireNonNull(dimension, "dimension");
    }
}
