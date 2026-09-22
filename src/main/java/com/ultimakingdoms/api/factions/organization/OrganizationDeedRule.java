package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Whitelisted trusted completion and its exact organizational credit. */
public record OrganizationDeedRule(ResourceLocation questId, int credit) {
    public OrganizationDeedRule {
        Objects.requireNonNull(questId, "questId");
        if (credit < 1 || credit > 10_000) {
            throw new IllegalArgumentException("deed credit must be between 1 and 10000");
        }
    }
}
