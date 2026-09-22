package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record OrganizationExplanation(ResourceLocation organizationId, ResourceLocation permission,
                                      Decision decision, Optional<ResourceLocation> effectiveRank,
                                      List<String> reasons, long evidenceRevision, long policyRevision) {
    public OrganizationExplanation {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(decision, "decision");
        effectiveRank = Objects.requireNonNull(effectiveRank, "effectiveRank");
        reasons = List.copyOf(reasons);
    }

    public OrganizationExplanation(ResourceLocation organizationId,ResourceLocation permission,Decision decision,
                                   Optional<ResourceLocation> rank,List<String> reasons,long evidenceRevision) {
        this(organizationId,permission,decision,rank,reasons,evidenceRevision,0);
    }
    public OrganizationExplanation withPolicyRevision(long revision) {
        return new OrganizationExplanation(organizationId,permission,decision,effectiveRank,reasons,evidenceRevision,revision);
    }

    public enum Decision {
        ALLOW,
        DENY,
        UNAVAILABLE
    }
}
