package com.ultimakingdoms.warfare;

import net.minecraftforge.common.ForgeConfigSpec;

/** R3 features default on; provider capability and actor authorization still govern each operation. */
public final class WarfareConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue MILITARY, CONTRACTS, TARGET_POLICY, WORLD_CONTEXT, PROTECT_CAPITALS, REQUIRE_DEFENDER_ONLINE;
    public static final ForgeConfigSpec.IntValue INTERVAL, BUDGET;
    public static final ForgeConfigSpec.IntValue NOTICE, CAMPAIGN_DURATION, MOBILIZATION_TICKS, UNIT_BUDGET;
    static {
        var builder = new ForgeConfigSpec.Builder().push("political_warfare");
        ENABLED = builder.comment("Enable R3 warfare, territory and civilian recovery features. Disabling preserves records.")
                .define("enabled", true);
        MILITARY = builder.define("militaryActions", true);
        CONTRACTS = builder.define("civilianContracts", true);
        TARGET_POLICY = builder.define("targetProtection", true);
        WORLD_CONTEXT = builder.define("worldContext", true);
        PROTECT_CAPITALS = builder.define("protectCapitals", true);
        REQUIRE_DEFENDER_ONLINE = builder.comment("Bound settlement sieges require the native defending leader online. Does not change unbound native claims.")
                .define("requireDefenderOnline", true);
        NOTICE = builder.defineInRange("campaignNoticeTicks", 1200, 20, 168000);
        CAMPAIGN_DURATION = builder.defineInRange("campaignDurationTicks", 168000, 1200, 2400000);
        MOBILIZATION_TICKS = builder.defineInRange("mobilizationTicks", 6000, 100, 24000);
        UNIT_BUDGET = builder.defineInRange("unitsPerCommander", 16, 1, 64);
        INTERVAL = builder.comment("Ticks between bounded native snapshot reconciliation batches.").defineInRange("reconcileInterval", 100, 20, 1200);
        BUDGET = builder.defineInRange("claimsPerBatch", 8, 1, 32);
        builder.pop();
        SPEC = builder.build();
    }
    private WarfareConfig() { }
}
