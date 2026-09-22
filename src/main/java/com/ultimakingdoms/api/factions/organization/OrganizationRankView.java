package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Set;

public record OrganizationRankView(ResourceLocation id, long minimumStanding,
                                   Set<ResourceLocation> permissions) {
    public OrganizationRankView {
        Objects.requireNonNull(id, "id");
        permissions = Set.copyOf(permissions);
    }
}
