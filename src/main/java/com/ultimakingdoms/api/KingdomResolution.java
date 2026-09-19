package com.ultimakingdoms.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record KingdomResolution(
        ResourceLocation kingdomId,
        AssignmentSource source,
        Optional<ResourceLocation> decisiveBiome,
        int priority,
        double confidence,
        List<String> trace
) {
    public KingdomResolution {
        Objects.requireNonNull(kingdomId, "kingdomId");
        Objects.requireNonNull(source, "source");
        decisiveBiome = Objects.requireNonNull(decisiveBiome, "decisiveBiome");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be a finite value from 0 to 1");
        }
        trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
    }
}
