package com.ultimakingdoms.integration;

import com.ultimakingdoms.api.gating.UnknownKingdomPolicy;
import net.minecraftforge.common.ForgeConfigSpec;

/** Common settings for the shared kingdom-gating integration layer. */
public final class IntegrationConfig {
    public static final String FILE_NAME = "ultima_kingdoms-integrations-common.toml";
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue KINGDOM_GATING_ENABLED;
    public static final ForgeConfigSpec.EnumValue<UnknownKingdomPolicy> DEFAULT_UNKNOWN_POLICY;
    public static final ForgeConfigSpec.BooleanValue CACHE_BY_SETTLEMENT_REVISION;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("kingdomGating");
        KINGDOM_GATING_ENABLED = builder
                .comment("Enable server-owned kingdom gates for optional addon content.")
                .define("enabled", true);
        DEFAULT_UNKNOWN_POLICY = builder
                .comment("Fallback used by integrations which omit when_unknown. Authored predicates remain authoritative.")
                .defineEnum("unknownKingdomPolicy", UnknownKingdomPolicy.DENY);
        CACHE_BY_SETTLEMENT_REVISION = builder
                .comment("Allow integration adapters to cache contexts until their settlement revision changes.")
                .define("cacheBySettlementRevision", true);
        builder.pop();
        SPEC = builder.build();
    }

    private IntegrationConfig() {
    }
}
