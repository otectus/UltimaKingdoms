package com.ultimakingdoms.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SettlementView {
    UUID id();

    ResourceKey<Level> dimension();

    BlockPos anchor();

    int radius();

    SettlementBounds bounds();

    ResourceLocation kingdomId();

    String displayName();

    ResourceLocation slug();

    ResourceLocation biomeAtCreation();

    Optional<ResourceLocation> styleId();

    AssignmentSource assignmentSource();

    DetectionSource detectionSource();

    boolean nameLocked();

    boolean kingdomLocked();

    long createdGameTime();

    long lastObservedGameTime();

    Map<String, String> externalRefs();

    List<String> aliases();

    List<String> assignmentTrace();

    long revision();
}
