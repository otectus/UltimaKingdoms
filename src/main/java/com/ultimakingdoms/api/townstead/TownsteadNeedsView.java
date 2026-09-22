package com.ultimakingdoms.api.townstead;

public record TownsteadNeedsView(
        int hunger, float saturation, float hungerExhaustion,
        int thirst, int quenched, float thirstExhaustion,
        int fatigue, boolean collapsed, boolean gated
) {
}
