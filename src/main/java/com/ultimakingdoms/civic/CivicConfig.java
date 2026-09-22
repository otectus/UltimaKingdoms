package com.ultimakingdoms.civic;

import net.minecraftforge.common.ForgeConfigSpec;

public final class CivicConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue INSTITUTIONAL_SERVICES;
    public static final ForgeConfigSpec.IntValue INTRODUCTION_RADIUS, CONTACT_RANGE, INTRODUCTION_COOLDOWN;
    static {
        var builder=new ForgeConfigSpec.Builder(); builder.push("regional_civic_network");
        ENABLED=builder.comment("Enable chapter services. Disabling preserves memberships, appointments and history.").define("enabled",true);
        INSTITUTIONAL_SERVICES=builder.comment("Enable R2 institutional commissions and civilian agreement effects. Requires verified providers; preserves accepted records when disabled.").define("institutionalServices",true);
        INTRODUCTION_RADIUS=builder.comment("Maximum distance between neighboring chapters; no chunks are loaded to find destinations.").defineInRange("introductionRadius",4096,128,32768);
        CONTACT_RANGE=builder.defineInRange("contactRange",8,2,16);
        INTRODUCTION_COOLDOWN=builder.comment("Ticks between new introductions per player and guild. Replays return the same destination.").defineInRange("introductionCooldown",24000,20,168000);
        builder.pop(); SPEC=builder.build();
    }
    private CivicConfig() { }
}
