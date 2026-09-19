package com.ultimakingdoms.core;

import com.ultimakingdoms.api.KingdomView;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;

record MissingKingdomView(ResourceLocation id) implements KingdomView {
    MissingKingdomView {
        Objects.requireNonNull(id, "id");
    }

    @Override
    public String translationKey() {
        return "kingdom." + UltimaKingdomsApi.MOD_ID + ".missing";
    }

    @Override
    public ResourceLocation namePool() {
        return new ResourceLocation(UltimaKingdomsApi.MOD_ID, "missing");
    }

    @Override
    public ResourceLocation heraldryIcon() {
        return new ResourceLocation(UltimaKingdomsApi.MOD_ID, "missing");
    }

    @Override
    public int uiColor() {
        return 0x777777;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of("missing_definition", "true");
    }
}
