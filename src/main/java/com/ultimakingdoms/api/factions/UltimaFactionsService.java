package com.ultimakingdoms.api.factions;

import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.McaCommunityRef;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public interface UltimaFactionsService {
    int apiVersion();

    Optional<FactionStandingSnapshot> getStanding(UUID playerId, ResourceLocation kingdomId);

    OptionalInt getLocalStanding(UUID playerId, McaCommunityRef community);

    FactionStandingResult apply(FactionStandingRequest request);

    /** Forces pending standing mutations and their idempotency receipts to stable storage. */
    boolean flushStandingChanges();

    EffectiveStanding effectiveStanding(UUID playerId, ResourceLocation kingdomId, OptionalInt localScore);

    boolean matches(StandingScope scope, UUID playerId, ResourceLocation kingdomId,
                    OptionalInt localScore, int minimum, int maximum);

    FactionMigrationReport previewLegacyMigration();

    FactionMigrationReport importLegacyMigration();

    LocalStandingEffectResult deliverLocalEffect(UUID playerId, McaCommunityRef community, int delta,
                                                  UUID correlationId, long sourceRevision,
                                                  String description);

    long revision();

    Registration registerMirror(FactionStandingMirror mirror);
}
