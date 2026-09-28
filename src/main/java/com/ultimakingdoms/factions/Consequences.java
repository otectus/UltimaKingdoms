package com.ultimakingdoms.factions;

import com.ultimakingdoms.api.factions.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import java.util.Optional;
import java.util.UUID;

/**
 * Standing consequences of political and military acts. Deltas are small against the operator range of ±2000 so that
 * a player's standing with a kingdom moves with their conduct without swamping quest and deed reputation.
 * Each application is keyed by the originating request id, so a replayed request never applies twice.
 */
public final class Consequences {
    /** Commander standing with the defending settlement's kingdom when a campaign is declared. */
    public static final int DECLARED_WAR = -40;
    /** Signer standing with the counterpart kingdom when an accord reaches full signature. */
    public static final int SIGNED_ACCORD = 25;
    /** Standing with the protector's kingdom when a voluntary protectorate obligation is fulfilled. */
    public static final int OBLIGATION_FULFILLED = 15;
    /** Standing with the protector's kingdom when a voluntary protectorate obligation is refused. */
    public static final int OBLIGATION_REFUSED = -15;
    private static final ResourceLocation SOURCE = new ResourceLocation("ultima_kingdoms", "consequence");
    /** Applies a standing change; a failure is logged and never blocks the act that caused it. */
    public static void standing(MinecraftServer server, UUID player, String kingdom, int delta, UUID correlation, String description) {
        if (player == null || kingdom == null || kingdom.isBlank() || delta == 0 || correlation == null) return;
        try {
            var id = new ResourceLocation(kingdom);
            UltimaFactionsApi.get(server).apply(new FactionStandingRequest(player, id, delta, SOURCE, FactionChangeCause.DIPLOMACY,
                    correlation, 0L, Optional.empty(), Optional.of(description), false));
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("Standing consequence skipped for {}: {}", kingdom, failure.toString());
        }
    }
    private Consequences() { }
}
