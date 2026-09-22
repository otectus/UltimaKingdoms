package com.ultimakingdoms.factions.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.concurrent.atomic.AtomicBoolean;

public final class FactionConfig {
    public enum SyncMode { OFF, SHADOW, MCA_TO_FACTION, BIDIRECTIONAL_SEMANTIC }
    public enum LegacyAggregation { MEAN, MEDIAN, MAX, MIN, SUM }
    public enum ReassignmentPolicy { FREEZE_HISTORY }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<SyncMode> SYNC_MODE;
    public static final ForgeConfigSpec.DoubleValue LOCAL_DELTA_CONTRIBUTION;
    public static final ForgeConfigSpec.BooleanValue PROPAGATE_DECAY;
    public static final ForgeConfigSpec.BooleanValue PROPAGATE_ADMIN;
    public static final ForgeConfigSpec.IntValue FACTION_OVERLAY_MAX;
    public static final ForgeConfigSpec.LongValue RECEIPT_RETENTION_TICKS;
    public static final ForgeConfigSpec.IntValue MISSING_MAPPING_CAPACITY;
    public static final ForgeConfigSpec.EnumValue<ReassignmentPolicy> REASSIGNMENT_POLICY;
    public static final ForgeConfigSpec.EnumValue<LegacyAggregation> LEGACY_AGGREGATION;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("reputationSync");
        SYNC_MODE = builder.defineEnum("mode", SyncMode.SHADOW);
        LOCAL_DELTA_CONTRIBUTION = builder.defineInRange("localDeltaContribution", 0.50D, 0D, 4D);
        PROPAGATE_DECAY = builder.define("propagateDecay", false);
        PROPAGATE_ADMIN = builder.define("propagateAdminChanges", false);
        FACTION_OVERLAY_MAX = builder.defineInRange("factionOverlayMax", 50, 0, 1_000);
        RECEIPT_RETENTION_TICKS = builder.defineInRange("receiptRetentionTicks", 12_096_000L, 1L, Long.MAX_VALUE);
        MISSING_MAPPING_CAPACITY = builder.defineInRange("missingMappingCapacity", 2_048, 1, 100_000);
        REASSIGNMENT_POLICY = builder.defineEnum("settlementReassignmentPolicy", ReassignmentPolicy.FREEZE_HISTORY);
        builder.pop();
        builder.push("migration");
        LEGACY_AGGREGATION = builder.defineEnum("legacyAggregation", LegacyAggregation.MEAN);
        builder.pop();
        SPEC = builder.build();
    }

    private FactionConfig() {
    }

    @SuppressWarnings("removal")
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC,
                    "ultima_kingdoms-factions-common.toml");
        }
    }
}
