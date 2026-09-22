package com.ultimakingdoms.api.gating;

import com.ultimakingdoms.api.CivicIdentityView;
import com.ultimakingdoms.api.KingdomView;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.SettlementView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Standard resolver for the five public subjects. It performs queries only. */
public final class DefaultKingdomContextResolver implements KingdomContextResolver {
    public static final DefaultKingdomContextResolver INSTANCE = new DefaultKingdomContextResolver();

    private DefaultKingdomContextResolver() {
    }

    @Override
    public Optional<KingdomContext> resolve(KingdomsService service, ServerPlayer player, Entity giver,
                                             KingdomSubject subject, Optional<UUID> explicitSettlementId) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(subject, "subject");
        explicitSettlementId = Objects.requireNonNull(explicitSettlementId, "explicitSettlementId");
        return switch (subject) {
            case PLAYER_LOCATION -> location(service, player);
            case GIVER_LOCATION -> giver == null ? Optional.empty() : location(service, giver);
            case GIVER_RESIDENCE -> giver == null ? Optional.empty() : residence(service, giver);
            case GIVER_ORIGIN -> giver == null ? Optional.empty() : origin(service, giver);
            case EXPLICIT_SETTLEMENT -> explicitSettlementId.flatMap(service::getSettlement)
                    .flatMap(settlement -> context(service, settlement, settlement.kingdomId()));
        };
    }

    private static Optional<KingdomContext> residence(KingdomsService service, Entity giver) {
        // Resolve the stored id back through the service so redirects and reassignment use current state.
        return service.getCivicIdentity(giver)
                .flatMap(CivicIdentityView::residenceSettlement)
                .flatMap(service::getSettlement)
                .flatMap(settlement -> context(service, settlement, settlement.kingdomId()));
    }

    private static Optional<KingdomContext> origin(KingdomsService service, Entity giver) {
        Optional<CivicIdentityView> identity = service.getCivicIdentity(giver);
        if (identity.isEmpty() || identity.get().originKingdom().isEmpty()
                || identity.get().originSettlement().isEmpty()) {
            return Optional.empty();
        }
        // Origin kingdom is historical. Never replace it with the settlement's current assignment.
        return service.getSettlement(identity.get().originSettlement().get())
                .flatMap(settlement -> context(service, settlement, identity.get().originKingdom().get()));
    }

    private static Optional<KingdomContext> location(KingdomsService service, Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return Optional.empty();
        return service.getSettlementAt(level, entity.blockPosition())
                .flatMap(settlement -> context(service, settlement, settlement.kingdomId()));
    }

    private static Optional<KingdomContext> context(KingdomsService service, SettlementView settlement,
                                                     net.minecraft.resources.ResourceLocation kingdomId) {
        return service.getKingdom(kingdomId).filter(KingdomView::defined).map(ignored -> new KingdomContext(
                settlement.id(), kingdomId, settlement.dimension().location(), settlement.revision()));
    }
}
