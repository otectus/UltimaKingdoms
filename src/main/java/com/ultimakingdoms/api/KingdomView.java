package com.ultimakingdoms.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public interface KingdomView {
    ResourceLocation id();

    String translationKey();

    ResourceLocation namePool();

    ResourceLocation heraldryIcon();

    int uiColor();

    Map<String, String> metadata();
}
