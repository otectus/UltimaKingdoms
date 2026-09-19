package com.ultimakingdoms.kingdom;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Objects;

public record BiomeRule(
        ResourceLocation id,
        ResourceLocation kingdomId,
        int priority,
        List<Selector> include,
        List<Selector> exclude,
        boolean environmental
) {
    public BiomeRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kingdomId, "kingdomId");
        include = List.copyOf(Objects.requireNonNull(include, "include"));
        exclude = List.copyOf(Objects.requireNonNull(exclude, "exclude"));
        if (include.isEmpty()) {
            throw new IllegalArgumentException("Biome rule " + id + " must include at least one selector");
        }
    }

    public Match match(Holder<Biome> biome) {
        if (exclude.stream().anyMatch(selector -> selector.matches(biome))) {
            return Match.NONE;
        }
        boolean tag = false;
        for (Selector selector : include) {
            if (selector.matches(biome)) {
                if (!selector.tag()) {
                    return Match.EXACT;
                }
                tag = true;
            }
        }
        return tag ? Match.TAG : Match.NONE;
    }

    public enum Match {
        NONE,
        TAG,
        EXACT
    }

    public record Selector(ResourceLocation id, boolean tag) {
        public Selector {
            Objects.requireNonNull(id, "id");
        }

        public boolean matches(Holder<Biome> biome) {
            if (tag) {
                return biome.is(TagKey.create(Registries.BIOME, id));
            }
            return biome.unwrapKey().map(ResourceKey::location).filter(id::equals).isPresent();
        }

        @Override
        public String toString() {
            return tag ? "#" + id : id.toString();
        }
    }
}
