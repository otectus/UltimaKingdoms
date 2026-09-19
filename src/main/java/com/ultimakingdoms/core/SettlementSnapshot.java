package com.ultimakingdoms.core;

import com.ultimakingdoms.api.AssignmentSource;
import com.ultimakingdoms.api.DetectionSource;
import com.ultimakingdoms.api.SettlementBounds;
import com.ultimakingdoms.api.SettlementView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SettlementSnapshot(
        UUID id,
        ResourceKey<Level> dimension,
        BlockPos anchor,
        int radius,
        SettlementBounds bounds,
        ResourceLocation kingdomId,
        String displayName,
        ResourceLocation slug,
        ResourceLocation biomeAtCreation,
        Optional<ResourceLocation> styleId,
        AssignmentSource assignmentSource,
        DetectionSource detectionSource,
        boolean nameLocked,
        boolean kingdomLocked,
        long createdGameTime,
        long lastObservedGameTime,
        Map<String, String> externalRefs,
        List<String> aliases,
        List<String> assignmentTrace,
        long revision
) implements SettlementView {
    public SettlementSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(kingdomId, "kingdomId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(biomeAtCreation, "biomeAtCreation");
        styleId = Objects.requireNonNull(styleId, "styleId");
        Objects.requireNonNull(assignmentSource, "assignmentSource");
        Objects.requireNonNull(detectionSource, "detectionSource");
        externalRefs = Map.copyOf(Objects.requireNonNull(externalRefs, "externalRefs"));
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        assignmentTrace = List.copyOf(Objects.requireNonNull(assignmentTrace, "assignmentTrace"));
    }
}
