package com.ultimakingdoms.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record SettlementCandidate(
        ResourceKey<Level> dimension,
        BlockPos anchor,
        int radius,
        SettlementBounds bounds,
        ResourceLocation sourceId,
        String sourceKey,
        DetectionSource detectionSource,
        Optional<ResourceLocation> styleId,
        Optional<ResourceLocation> explicitKingdom,
        Map<String, String> externalRefs,
        Optional<String> proposedName
) {
    public SettlementCandidate {
        Objects.requireNonNull(dimension, "dimension");
        anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        if (radius < 0) {
            throw new IllegalArgumentException("Settlement radius must be non-negative");
        }
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(sourceId, "sourceId");
        if (Objects.requireNonNull(sourceKey, "sourceKey").isBlank()) {
            throw new IllegalArgumentException("Settlement source key must not be blank");
        }
        Objects.requireNonNull(detectionSource, "detectionSource");
        styleId = Objects.requireNonNull(styleId, "styleId");
        explicitKingdom = Objects.requireNonNull(explicitKingdom, "explicitKingdom");
        externalRefs = Map.copyOf(Objects.requireNonNull(externalRefs, "externalRefs"));
        proposedName = Objects.requireNonNull(proposedName, "proposedName")
                .map(String::strip)
                .filter(name -> !name.isEmpty());
    }

    public static SettlementCandidate structure(
            ResourceKey<Level> dimension,
            BlockPos anchor,
            int radius,
            ResourceLocation detectorId,
            String structureKey,
            ResourceLocation styleId
    ) {
        return new SettlementCandidate(dimension, anchor, radius, SettlementBounds.around(anchor, radius),
                detectorId, structureKey, DetectionSource.STRUCTURE, Optional.ofNullable(styleId),
                Optional.empty(), Map.of(), Optional.empty());
    }

    public static SettlementCandidate manual(
            ResourceKey<Level> dimension,
            BlockPos anchor,
            int radius,
            ResourceLocation detectorId,
            String sourceKey,
            String proposedName
    ) {
        return new SettlementCandidate(dimension, anchor, radius, SettlementBounds.around(anchor, radius),
                detectorId, sourceKey, DetectionSource.MANUAL, Optional.empty(), Optional.empty(),
                Map.of(), Optional.ofNullable(proposedName));
    }

    public static SettlementCandidate external(
            ResourceKey<Level> dimension,
            BlockPos anchor,
            int radius,
            SettlementBounds bounds,
            ResourceLocation detectorId,
            String sourceKey,
            ResourceLocation explicitKingdom,
            Map<String, String> externalRefs,
            String proposedName
    ) {
        return new SettlementCandidate(dimension, anchor, radius, bounds, detectorId, sourceKey,
                DetectionSource.EXTERNAL, Optional.empty(), Optional.ofNullable(explicitKingdom),
                externalRefs, Optional.ofNullable(proposedName));
    }
}
