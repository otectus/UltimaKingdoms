package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record OrganizationDefinitionView(ResourceLocation id, String kind, boolean military, String nameKey,
                                         String descriptionKey, Optional<ResourceLocation> exclusiveGroup,
                                         Set<ResourceLocation> conflicts, List<OrganizationRankView> ranks,
                                         List<OrganizationServiceRule> services,
                                         List<OrganizationDeedRule> deeds, long definitionRevision) {
    public OrganizationDefinitionView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(nameKey, "nameKey");
        Objects.requireNonNull(descriptionKey, "descriptionKey");
        exclusiveGroup = Objects.requireNonNull(exclusiveGroup, "exclusiveGroup");
        conflicts = Set.copyOf(conflicts);
        ranks = List.copyOf(ranks);
        services = List.copyOf(services);
        deeds = List.copyOf(deeds);
    }
}
