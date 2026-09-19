package com.ultimakingdoms.presentation;

import net.minecraftforge.common.ForgeConfigSpec;

public final class PresentationConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue SHOW_SETTLEMENT_OVERLAY;
    public static final ForgeConfigSpec.BooleanValue KINGDOM_BORDERS_ONLY;
    public static final ForgeConfigSpec.IntValue OVERLAY_DURATION_TICKS;
    public static final ForgeConfigSpec.IntValue OVERLAY_Y;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("entryOverlay");
        SHOW_SETTLEMENT_OVERLAY = builder
                .comment("Show a localized title when entering a recognized settlement.")
                .define("enabled", true);
        KINGDOM_BORDERS_ONLY = builder
                .comment("Only show the title when the kingdom changes, instead of for every settlement entry.")
                .define("kingdomBordersOnly", false);
        OVERLAY_DURATION_TICKS = builder
                .comment("How long an entry title remains visible, in client ticks.")
                .defineInRange("durationTicks", 80, 20, 400);
        OVERLAY_Y = builder
                .comment("Vertical screen position of the entry title, in pixels from the top.")
                .defineInRange("y", 54, 0, 1000);
        builder.pop();
        SPEC = builder.build();
    }

    private PresentationConfig() {
    }
}
