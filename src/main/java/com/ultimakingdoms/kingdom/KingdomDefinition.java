package com.ultimakingdoms.kingdom;

import com.ultimakingdoms.api.KingdomView;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record KingdomDefinition(
        ResourceLocation id,
        String translationKey,
        ResourceLocation namePool,
        ResourceLocation heraldryIcon,
        int uiColor,
        Map<String, String> metadata,
        Set<ResourceLocation> styleHints,
        boolean fallback
) implements KingdomView {
    public KingdomDefinition {
        Objects.requireNonNull(id, "id");
        if (Objects.requireNonNull(translationKey, "translationKey").isBlank()) {
            throw new IllegalArgumentException("translationKey must not be blank");
        }
        Objects.requireNonNull(namePool, "namePool");
        Objects.requireNonNull(heraldryIcon, "heraldryIcon");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        styleHints = Set.copyOf(Objects.requireNonNull(styleHints, "styleHints"));
    }
}
