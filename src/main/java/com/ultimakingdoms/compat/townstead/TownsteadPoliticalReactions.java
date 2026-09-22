package com.ultimakingdoms.compat.townstead;

import com.ultimakingdoms.api.CivicIdentityView;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.SettlementView;
import com.ultimakingdoms.api.event.SettlementKingdomChangedEvent;
import com.ultimakingdoms.api.factions.event.FactionStandingChangedEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded semantic dispatch; Townstead remains responsible for reaction effects and animation. */
final class TownsteadPoliticalReactions {
    private static final int PLAYER_RADIUS = 24;
    private static final int MAX_PLAYER_TARGETS = 32;
    private static final int MAX_SETTLEMENT_TARGETS = 64;
    private static final ResourceLocation STANDING_IMPROVED =
            new ResourceLocation("ultima_kingdoms", "standing_improved");
    private static final ResourceLocation STANDING_WORSENED =
            new ResourceLocation("ultima_kingdoms", "standing_worsened");
    private static final ResourceLocation ALLEGIANCE_CHANGED =
            new ResourceLocation("ultima_kingdoms", "settlement_allegiance_changed");

    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadServiceImpl townstead;

    TownsteadPoliticalReactions(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl townstead) {
        this.server = server;
        this.kingdoms = kingdoms;
        this.townstead = townstead;
    }

    @SubscribeEvent
    public void standingChanged(FactionStandingChangedEvent event) {
        if (!TownsteadIntegrationConfig.ENABLE_REACTIONS.get() || !event.result().applied()
                || event.result().appliedDelta() == 0 || event.request().quiet()) return;
        ServerPlayer player = server.getPlayerList().getPlayer(event.request().playerId());
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        ResourceLocation kingdomId = event.result().standing().kingdomId();
        ResourceLocation reactionId = event.result().appliedDelta() > 0
                ? STANDING_IMPROVED : STANDING_WORSENED;
        Set<String> tags = Set.of(
                "kingdom:" + kingdomId,
                "standing:" + event.result().standing().tierId(),
                "cause:" + event.request().cause().name().toLowerCase(Locale.ROOT),
                "context:faction");
        int dispatched = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(PLAYER_RADIUS), candidate -> candidate != player)) {
            if (dispatched >= MAX_PLAYER_TARGETS) break;
            if (!belongsToKingdom(entity, kingdomId) || townstead.villager(entity).isEmpty()) continue;
            if (townstead.fireReaction(level, entity, Optional.of(player), reactionId, tags)) dispatched++;
        }
    }

    @SubscribeEvent
    public void settlementChanged(SettlementKingdomChangedEvent event) {
        if (!TownsteadIntegrationConfig.ENABLE_REACTIONS.get()) return;
        SettlementView settlement = event.settlement();
        ServerLevel level = server.getLevel(settlement.dimension());
        if (level == null) return;
        var bounds = settlement.bounds();
        AABB area = new AABB(bounds.minX(), level.getMinBuildHeight(), bounds.minZ(),
                bounds.maxX() + 1.0D, level.getMaxBuildHeight(), bounds.maxZ() + 1.0D);
        Set<String> tags = Set.of(
                "kingdom_old:" + event.oldKingdom(),
                "kingdom_new:" + event.newKingdom(),
                "reason:" + event.reason().name().toLowerCase(Locale.ROOT),
                "context:settlement");
        int dispatched = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (dispatched >= MAX_SETTLEMENT_TARGETS) break;
            if (!residesIn(entity, settlement.id()) || townstead.villager(entity).isEmpty()) continue;
            if (townstead.fireReaction(level, entity, Optional.empty(), ALLEGIANCE_CHANGED, tags)) dispatched++;
        }
    }

    private boolean belongsToKingdom(LivingEntity entity, ResourceLocation kingdomId) {
        return kingdoms.getCivicIdentity(entity).flatMap(CivicIdentityView::residenceSettlement)
                .flatMap(kingdoms::getSettlement)
                .map(SettlementView::kingdomId)
                .filter(kingdomId::equals)
                .isPresent();
    }

    private boolean residesIn(LivingEntity entity, UUID settlementId) {
        return kingdoms.getCivicIdentity(entity).flatMap(CivicIdentityView::residenceSettlement)
                .filter(settlementId::equals).isPresent();
    }
}
