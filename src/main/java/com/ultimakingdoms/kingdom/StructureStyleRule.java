package com.ultimakingdoms.kingdom;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public record StructureStyleRule(
        ResourceLocation id,
        ResourceLocation kingdomId,
        int priority,
        Set<ResourceLocation> styles
) {
    public StructureStyleRule {
        styles = Set.copyOf(styles);
        if (styles.isEmpty()) {
            throw new IllegalArgumentException("Structure style rule " + id + " must contain styles");
        }
    }
}
