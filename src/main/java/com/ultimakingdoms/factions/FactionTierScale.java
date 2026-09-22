package com.ultimakingdoms.factions;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

final class FactionTierScale {
    static final ResourceLocation LADDER = new ResourceLocation("ultima_kingdoms", "faction_standing");
    private static final List<Tier> TIERS = List.of(
            new Tier("enemy", Integer.MIN_VALUE),
            new Tier("hostile", -300),
            new Tier("unfriendly", -100),
            new Tier("neutral", -24),
            new Tier("trusted", 100),
            new Tier("honored", 300),
            new Tier("exalted", 700));

    private FactionTierScale() {
    }

    static String tier(int score) {
        String result = TIERS.get(0).id;
        for (Tier tier : TIERS) {
            if (score < tier.minimum) break;
            result = tier.id;
        }
        return result;
    }

    static int rank(String id) {
        for (int i = 0; i < TIERS.size(); i++) if (TIERS.get(i).id.equals(id)) return i;
        return 0;
    }

    private record Tier(String id, int minimum) {
    }
}
