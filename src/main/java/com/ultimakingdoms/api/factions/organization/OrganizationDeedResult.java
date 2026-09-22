package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OrganizationDeedResult(Status status, ResourceLocation organizationId, UUID effectId,
                                     int requestedCredit, int appliedCredit,
                                     Optional<OrganizationMembershipSnapshot> membership,
                                     Optional<OrganizationEvidenceReceipt> receipt, String reason) {
    public OrganizationDeedResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(effectId, "effectId");
        membership = Objects.requireNonNull(membership, "membership");
        receipt = Objects.requireNonNull(receipt, "receipt");
        reason = Objects.requireNonNull(reason, "reason");
    }

    public boolean applied() {
        return status == Status.APPLIED;
    }

    public enum Status {
        APPLIED,
        REPLAYED,
        CONFLICTING_REPLAY,
        UNKNOWN_DEFINITION,
        NOT_A_MEMBER,
        INVALID_EVIDENCE,
        READ_ONLY,
        EVIDENCE_CAPACITY_REACHED,
        DURABILITY_FAILED
    }
}
