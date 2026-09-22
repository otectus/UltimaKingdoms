package com.ultimakingdoms.api.townstead;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record TownsteadOriginView(
        ResourceLocation id, String displayName, Optional<ResourceLocation> species,
        Optional<ResourceLocation> ancestry, Optional<ResourceLocation> lineage,
        Optional<ResourceLocation> effectiveSpecies, List<ResourceLocation> defaultGenes,
        List<TownsteadLifeStageView> lifeStages
) {
    public TownsteadOriginView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        species = Objects.requireNonNull(species, "species");
        ancestry = Objects.requireNonNull(ancestry, "ancestry");
        lineage = Objects.requireNonNull(lineage, "lineage");
        effectiveSpecies = Objects.requireNonNull(effectiveSpecies, "effectiveSpecies");
        defaultGenes = List.copyOf(Objects.requireNonNull(defaultGenes, "defaultGenes"));
        lifeStages = List.copyOf(Objects.requireNonNull(lifeStages, "lifeStages"));
    }
}
