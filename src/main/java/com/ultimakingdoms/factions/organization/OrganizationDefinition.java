package com.ultimakingdoms.factions.organization;

import com.ultimakingdoms.api.factions.organization.OrganizationDefinitionView;
import com.ultimakingdoms.api.factions.organization.OrganizationDeedRule;
import com.ultimakingdoms.api.factions.organization.OrganizationRankView;
import com.ultimakingdoms.api.factions.organization.OrganizationServiceRule;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

record OrganizationDefinition(ResourceLocation id, String kind, boolean military, String nameKey, String descriptionKey,
                              Optional<ResourceLocation> exclusiveGroup, Set<ResourceLocation> conflicts,
                              List<OrganizationRankView> ranks, List<OrganizationServiceRule> services,
                              List<OrganizationDeedRule> deeds) {
    OrganizationDefinition {
        conflicts = Set.copyOf(conflicts);
        ranks = List.copyOf(ranks);
        services = List.copyOf(services);
        deeds = List.copyOf(deeds);
    }

    OrganizationDefinitionView view(long generation) {
        return new OrganizationDefinitionView(id, kind, military, nameKey, descriptionKey, exclusiveGroup,
                conflicts, ranks, services, deeds, generation);
    }

    Optional<OrganizationRankView> rank(long standing) {
        OrganizationRankView result = null;
        for (OrganizationRankView candidate : ranks) {
            if (standing < candidate.minimumStanding()) break;
            result = candidate;
        }
        return Optional.ofNullable(result);
    }

    Optional<OrganizationServiceRule> service(ResourceLocation permission) {
        return services.stream().filter(value -> value.permission().equals(permission)).findFirst();
    }

    Optional<OrganizationDeedRule> deed(ResourceLocation questId) {
        return deeds.stream().filter(value -> value.questId().equals(questId)).findFirst();
    }

    int rankIndex(ResourceLocation rank) {
        for (int index = 0; index < ranks.size(); index++) {
            if (ranks.get(index).id().equals(rank)) return index;
        }
        return -1;
    }

    Map<ResourceLocation, Integer> rankIndexes() {
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < ranks.size(); index++) result.put(ranks.get(index).id(), index);
        return Map.copyOf(result);
    }
}
