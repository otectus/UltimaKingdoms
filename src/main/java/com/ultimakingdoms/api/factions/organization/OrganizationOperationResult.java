package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record OrganizationOperationResult(Status status, ResourceLocation organizationId,
                                          Optional<OrganizationMembershipSnapshot> membership,
                                          String reason) {
    public OrganizationOperationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(organizationId, "organizationId");
        membership = Objects.requireNonNull(membership, "membership");
        reason = Objects.requireNonNull(reason, "reason");
    }

    public boolean applied() {
        return status == Status.APPLIED;
    }

    public enum Status {
        APPLIED,
        NO_CHANGE,
        UNKNOWN_DEFINITION,
        INCOMPATIBLE_MEMBERSHIP,
        READ_ONLY,
        LIMIT_REACHED,
        DURABILITY_FAILED
    }
}
