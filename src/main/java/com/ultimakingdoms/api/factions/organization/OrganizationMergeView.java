package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OrganizationMergeView(UUID id, ResourceLocation source, ResourceLocation target, UUID proposer,
                                    boolean sourceConsent, boolean targetConsent, Set<UUID> memberOptOuts,
                                    State state, long revision) {
    public enum State { PENDING, CONSENTED, APPLIED, CANCELLED }
    public OrganizationMergeView { Objects.requireNonNull(id);Objects.requireNonNull(source);Objects.requireNonNull(target);
        Objects.requireNonNull(proposer);Objects.requireNonNull(state);memberOptOuts=Set.copyOf(memberOptOuts); }
}
