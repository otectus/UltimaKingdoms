package com.ultimakingdoms.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public interface KingdomView {
    /** False for a retained kingdom id whose datapack definition is currently unavailable. */
    default boolean defined() {
        return true;
    }

    ResourceLocation id();

    String translationKey();

    ResourceLocation namePool();

    ResourceLocation heraldryIcon();

    int uiColor();

    Map<String, String> metadata();
}
