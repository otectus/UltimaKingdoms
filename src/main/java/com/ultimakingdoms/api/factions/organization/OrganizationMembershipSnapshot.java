package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record OrganizationMembershipSnapshot(ResourceLocation organizationId, Status status,
                                             boolean definitionAvailable, long standing, int deedCount,
                                             Optional<ResourceLocation> rank, long joinedAt, long leftAt,
                                             long revision, List<OrganizationEvidenceReceipt> recentEvidence) {
    public OrganizationMembershipSnapshot {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(status, "status");
        rank = Objects.requireNonNull(rank, "rank");
        recentEvidence = List.copyOf(recentEvidence);
    }

    public enum Status {
        ACTIVE,
        LEFT,
        /** Service evidence exists, but the player has never joined this organization. */
        UNAFFILIATED
    }
}
