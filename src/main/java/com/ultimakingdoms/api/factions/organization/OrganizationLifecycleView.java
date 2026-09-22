package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OrganizationLifecycleView(ResourceLocation id, ResourceLocation template, ResourceLocation canonicalId,
                                        String sponsorKingdom, String displayName, State state, UUID founder,
                                        long createdAt, long revision, Optional<ResourceLocation> redirect) {
    public enum State { ACTIVE, MERGED, DISSOLVED }
    public OrganizationLifecycleView {
        Objects.requireNonNull(id); Objects.requireNonNull(template); Objects.requireNonNull(canonicalId);
        Objects.requireNonNull(sponsorKingdom); Objects.requireNonNull(displayName); Objects.requireNonNull(state);
        Objects.requireNonNull(founder); redirect = redirect == null ? Optional.empty() : redirect;
    }
}
