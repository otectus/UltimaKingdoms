package com.ultimakingdoms.api.townstead;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record TownsteadGeneView(
        ResourceLocation id, String displayName, String description, String category,
        String dominance, Optional<ResourceLocation> locus, int weight, String displayMode,
        List<TownsteadGeneVariantView> variants
) {
    public TownsteadGeneView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(dominance, "dominance");
        locus = Objects.requireNonNull(locus, "locus");
        Objects.requireNonNull(displayMode, "displayMode");
        variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
    }
}
