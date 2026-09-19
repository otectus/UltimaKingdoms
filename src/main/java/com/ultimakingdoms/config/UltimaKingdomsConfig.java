package com.ultimakingdoms.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class UltimaKingdomsConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue DEFAULT_SETTLEMENT_RADIUS;
    public static final ForgeConfigSpec.IntValue POI_SEARCH_RADIUS;
    public static final ForgeConfigSpec.IntValue POI_MINIMUM_COUNT;
    public static final ForgeConfigSpec.IntValue CHUNK_SCANS_PER_TICK;
    public static final ForgeConfigSpec.IntValue CANDIDATE_MERGE_DISTANCE;
    public static final ForgeConfigSpec.IntValue CIVIC_EVIDENCE_INTERVAL;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("settlementDiscovery");
        DEFAULT_SETTLEMENT_RADIUS = builder
                .comment("Default horizontal territory radius for detected settlements.")
                .defineInRange("defaultSettlementRadius", 64, 16, 512);
        POI_SEARCH_RADIUS = builder
                .comment("Radius searched for village POIs when a loaded chunk is inspected.")
                .defineInRange("poiSearchRadius", 48, 16, 256);
        POI_MINIMUM_COUNT = builder
                .comment("Minimum village POIs needed to recognize a settlement.")
                .defineInRange("poiMinimumCount", 3, 1, 64);
        CHUNK_SCANS_PER_TICK = builder
                .comment("Maximum queued loaded chunks inspected per server tick.")
                .defineInRange("chunkScansPerTick", 2, 1, 64);
        CANDIDATE_MERGE_DISTANCE = builder
                .comment("Maximum anchor distance for weak candidates to match an existing settlement.")
                .defineInRange("candidateMergeDistance", 32, 0, 256);
        builder.pop();
        builder.push("civicIdentity");
        CIVIC_EVIDENCE_INTERVAL = builder
                .comment("Ticks between positive home-evidence checks for each loaded NPC.")
                .defineInRange("evidenceInterval", 200, 20, 24_000);
        builder.pop();
        SPEC = builder.build();
    }

    private UltimaKingdomsConfig() {
    }
}
