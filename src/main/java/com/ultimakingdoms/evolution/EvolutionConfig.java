package com.ultimakingdoms.evolution;

import net.minecraftforge.common.ForgeConfigSpec;

public final class EvolutionConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue INTERVAL, BUDGET, CONCURRENT, DIGEST_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue AUTO_ELIGIBLE;
    static {
        var b = new ForgeConfigSpec.Builder();
        INTERVAL = b.comment("Ticks between active-region evaluations. Each world must also opt in explicitly.").defineInRange("interval", 1200, 200, 24000);
        BUDGET = b.comment("Maximum online players/regions inspected per evaluation; no chunk loads.").defineInRange("regionBudget", 4, 1, 16);
        CONCURRENT = b.defineInRange("concurrentScenarios", 8, 1, 64);
        DIGEST_INTERVAL = b.defineInRange("digestInterval", 24000, 1200, 240000);
        AUTO_ELIGIBLE = b.comment("Treat every settlement whose kingdom has a constituted government as an eligible region unless an operator pauses it; the world opt-in still applies.").define("autoEligibleRegions", true);
        SPEC = b.build();
    }
    private EvolutionConfig() { }
}
