package com.ultimakingdoms.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface KingdomsService {
    Optional<KingdomView> getKingdom(ResourceLocation id);

    Collection<KingdomView> getKingdoms();

    Optional<SettlementView> getSettlement(UUID id);

    Optional<SettlementView> getSettlementAt(ServerLevel level, BlockPos pos);

    Collection<SettlementView> getSettlements(ResourceLocation kingdomId);

    List<SettlementView> getSettlementPage(Optional<ResourceLocation> kingdomId, int offset, int limit);

    Optional<SettlementView> findSettlement(String query);

    Optional<SettlementView> getResidence(Entity entity);

    Optional<CivicIdentityView> getCivicIdentity(Entity entity);

    Map<String, String> getContext(Entity entity);

    long revision();

    SettlementView registerCandidate(ServerLevel level, SettlementCandidate candidate);

    SettlementView rename(UUID settlementId, String newName);

    SettlementView setKingdom(UUID settlementId, ResourceLocation kingdomId);

    SettlementView reclassify(UUID settlementId);

    SettlementView setLocks(UUID settlementId, boolean nameLocked, boolean kingdomLocked);

    SettlementView merge(UUID sourceId, UUID targetId);

    Collection<SettlementView> discover(ServerLevel level, BlockPos center, int radiusChunks);

    CivicIdentityView setOrigin(Entity entity, UUID settlementId);

    CivicIdentityView setResidence(Entity entity, UUID settlementId);

    Registration registerSettlementDetector(ResourceLocation owner, SettlementDetector detector);

    Registration registerKingdomResolver(ResourceLocation owner, KingdomResolver resolver);

    Registration registerCivicEvidenceProvider(ResourceLocation owner, CivicEvidenceProvider provider);
}
