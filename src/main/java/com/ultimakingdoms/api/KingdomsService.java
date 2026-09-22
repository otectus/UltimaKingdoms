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

    Optional<SettlementView> getSettlementByExternalRef(String namespace, String value);

    default Optional<SettlementView> getSettlementForMcaVillage(ResourceLocation dimension, int villageId) {
        McaCommunityRef reference = new McaCommunityRef(dimension, villageId);
        return getSettlementByExternalRef(McaCommunityRef.EXTERNAL_REF_NAMESPACE, reference.format());
    }

    default Optional<SettlementView> getSettlementByMcaVillage(ResourceLocation dimension, int villageId) {
        return getSettlementForMcaVillage(dimension, villageId);
    }

    Optional<SettlementView> getSettlementAt(ServerLevel level, BlockPos pos);

    Collection<SettlementView> getSettlements(ResourceLocation kingdomId);

    List<SettlementView> getSettlementPage(Optional<ResourceLocation> kingdomId, int offset, int limit);

    Optional<SettlementView> findSettlement(String query);

    Optional<SettlementView> getResidence(Entity entity);

    /** All provider aliases, including references retained through merges. */
    default java.util.Map<String, java.util.Set<String>> getSettlementExternalRefs(UUID settlementId) {
        java.util.Map<String, java.util.Set<String>> refs = new java.util.LinkedHashMap<>();
        getSettlement(settlementId).ifPresent(v -> v.externalRefs().forEach((key, value) -> refs.put(key, java.util.Set.of(value))));
        return java.util.Map.copyOf(refs);
    }

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
