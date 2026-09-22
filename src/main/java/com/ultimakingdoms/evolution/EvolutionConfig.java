package com.ultimakingdoms.evolution;

import net.minecraftforge.common.ForgeConfigSpec;

public final class EvolutionConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue INTERVAL, BUDGET, CONCURRENT, DIGEST_INTERVAL;
    static {
        var b = new ForgeConfigSpec.Builder();
        INTERVAL = b.comment("Ticks between active-region evaluations. Each world must also opt in explicitly.").defineInRange("interval", 1200, 200, 24000);
        BUDGET = b.comment("Maximum online players/regions inspected per evaluation; no chunk loads.").defineInRange("regionBudget", 4, 1, 16);
        CONCURRENT = b.defineInRange("concurrentScenarios", 8, 1, 64);
        DIGEST_INTERVAL = b.defineInRange("digestInterval", 24000, 1200, 240000);
        SPEC = b.build();
    }
    private EvolutionConfig() { }
}
