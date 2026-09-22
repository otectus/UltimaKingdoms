package com.ultimakingdoms.api.townstead;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TownsteadVillagerView(
        UUID entityId, String name, ResourceLocation entityType, Optional<ResourceLocation> rootId,
        String lifeStage, long biologicalAgeDays, int apparentAgeYears,
        boolean immortal, boolean ageless, boolean senior, String personalityId,
        Optional<ResourceLocation> professionId, int professionLevel, int professionXp, float fertility,
        TownsteadScheduleView schedule, TownsteadNeedsView needs,
        Map<String, String> carriedVariants, List<String> expressedAlleles,
        Map<ResourceLocation, Float> heritage
) {
    public TownsteadVillagerView {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(entityType, "entityType");
        rootId = Objects.requireNonNull(rootId, "rootId");
        Objects.requireNonNull(lifeStage, "lifeStage");
        Objects.requireNonNull(personalityId, "personalityId");
        professionId = Objects.requireNonNull(professionId, "professionId");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(needs, "needs");
        carriedVariants = Map.copyOf(Objects.requireNonNull(carriedVariants, "carriedVariants"));
        expressedAlleles = List.copyOf(Objects.requireNonNull(expressedAlleles, "expressedAlleles"));
        heritage = Map.copyOf(Objects.requireNonNull(heritage, "heritage"));
    }
}
