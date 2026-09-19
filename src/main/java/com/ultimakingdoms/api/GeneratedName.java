package com.ultimakingdoms.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record GeneratedName(
        String displayName,
        String normalizedKey,
        ResourceLocation slug,
        ResourceLocation poolId,
        Optional<String> recipe
) {
    public GeneratedName {
        displayName = requireText(displayName, "displayName");
        normalizedKey = requireText(normalizedKey, "normalizedKey");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(poolId, "poolId");
        recipe = Objects.requireNonNull(recipe, "recipe");
    }

    private static String requireText(String value, String field) {
        String result = Objects.requireNonNull(value, field).strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }
}
