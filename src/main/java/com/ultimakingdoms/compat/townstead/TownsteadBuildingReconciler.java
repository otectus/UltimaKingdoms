package com.ultimakingdoms.compat.townstead;

import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.SettlementView;
import com.ultimakingdoms.api.townstead.TownsteadBuildingFingerprint;
import com.ultimakingdoms.api.townstead.TownsteadBuildingView;
import com.ultimakingdoms.api.townstead.event.TownsteadBuildingsChangedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded polling for loaded settlements that are active around online players. */
final class TownsteadBuildingReconciler {
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadServiceImpl townstead;
    private final Map<UUID, List<TownsteadBuildingFingerprint>> fingerprints = new LinkedHashMap<>();

    TownsteadBuildingReconciler(MinecraftServer server, KingdomsService kingdoms, TownsteadServiceImpl townstead) {
        this.server = server;
        this.kingdoms = kingdoms;
        this.townstead = townstead;
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() != server
                || server.getTickCount() % TownsteadIntegrationConfig.BUILDING_RECONCILE_INTERVAL.get() != 0) {
            return;
        }
        int limit = TownsteadIntegrationConfig.MAX_ACTIVE_SETTLEMENTS.get();
        Map<UUID, Active> active = new LinkedHashMap<>();
        for (var player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel level)) continue;
            kingdoms.getSettlementAt(level, player.blockPosition()).ifPresent(settlement -> {
                if (active.size() < limit) active.putIfAbsent(settlement.id(), new Active(level, settlement));
            });
        }
        Set<UUID> retained = new LinkedHashSet<>();
        active.forEach((id, context) -> {
            retained.add(id);
            List<TownsteadBuildingFingerprint> current = townstead.buildings(context.level(), id).stream()
                    .map(TownsteadBuildingReconciler::fingerprint).toList();
            List<TownsteadBuildingFingerprint> previous = fingerprints.put(id, current);
            if (previous != null && !previous.equals(current)) {
                townstead.reconciledChange();
                MinecraftForge.EVENT_BUS.post(new TownsteadBuildingsChangedEvent(
                        id, context.level().dimension().location(), previous, current));
            }
        });
        fingerprints.keySet().retainAll(retained);
    }

    void clear() {
        fingerprints.clear();
    }

    private static TownsteadBuildingFingerprint fingerprint(TownsteadBuildingView building) {
        return new TownsteadBuildingFingerprint(
                building.bindingId(), building.buildingId(), building.type(), building.size());
    }

    private record Active(ServerLevel level, SettlementView settlement) {
    }
}
