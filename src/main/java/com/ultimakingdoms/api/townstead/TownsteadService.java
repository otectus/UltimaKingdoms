package com.ultimakingdoms.api.townstead;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface TownsteadService {
    int apiVersion();

    Set<TownsteadCapability> capabilities();

    Map<TownsteadCapability, String> diagnostics();

    Optional<TownsteadVillagerView> villager(Entity entity);

    Optional<TownsteadCalendarView> calendar();

    Optional<TownsteadBuildingView> buildingAt(ServerLevel level, BlockPos pos);

    /** Directly queries one loaded MCA village resolved from the settlement's indexed external ref. */
    List<TownsteadBuildingView> buildings(ServerLevel level, UUID settlementId);

    Optional<TownsteadOriginView> origin(ResourceLocation id);

    Optional<TownsteadGeneView> gene(ResourceLocation id);

    Optional<TownsteadSpiritView> spirit(ServerLevel level, UUID settlementId);

    default BoundCivicBuilding bind(TownsteadBuildingView building) {
        return BoundCivicBuilding.from(building);
    }

    BuildingRecoveryResult recover(ServerLevel level, BoundCivicBuilding binding, BuildingRecoveryPolicy policy);

    boolean fireReaction(ServerLevel level, LivingEntity villager, Optional<Player> playerCause,
                         ResourceLocation reactionId, Set<String> contextTags);

    long revision();
}
