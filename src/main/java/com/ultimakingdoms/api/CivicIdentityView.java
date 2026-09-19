package com.ultimakingdoms.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

public interface CivicIdentityView {
    Optional<UUID> originSettlement();

    Optional<ResourceLocation> originKingdom();

    Optional<UUID> residenceSettlement();

    Optional<ResourceLocation> residenceKingdom();

    CivicIdentitySource source();

    long lastResidenceChange();
}
