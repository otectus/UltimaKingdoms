package com.ultimakingdoms.api.politics;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** Trusted server read. Player-facing consumers must enforce discovery before disclosing location. */
public record InstitutionView(UUID id, ResourceLocation kingdom, UUID settlement,
                              ResourceLocation dimension, BlockPos position, String type,
                              String titleKey, Status status, long revision) {
    public enum Status { AVAILABLE, UNLOADED, PROVIDER_UNAVAILABLE, SUSPENDED, DEFINITION_UNAVAILABLE, BUILDING_CHANGED }
    public InstitutionView { position = position.immutable(); }
    public boolean operational() { return status == Status.AVAILABLE; }
}
