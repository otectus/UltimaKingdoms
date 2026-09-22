package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record OrganizationServiceRule(ResourceLocation permission, ResourceLocation minimumRank,
                                      long minimumStanding, int requiredDeeds, long neutralMinimumStanding, int neutralRequiredDeeds) {
    public OrganizationServiceRule(ResourceLocation permission, ResourceLocation minimumRank,
                                   long minimumStanding, int requiredDeeds) {
        this(permission, minimumRank, minimumStanding, requiredDeeds, -1, -1);
    }
    public boolean neutralAlternative() { return neutralMinimumStanding >= 0 && neutralRequiredDeeds >= 0; }
    public OrganizationServiceRule {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(minimumRank, "minimumRank");
        if ((neutralMinimumStanding < 0) != (neutralRequiredDeeds < 0)) throw new IllegalArgumentException("Incomplete neutral alternative");
        if (requiredDeeds < 0) throw new IllegalArgumentException("requiredDeeds must not be negative");
    }
}
