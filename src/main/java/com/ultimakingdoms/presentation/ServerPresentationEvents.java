package com.ultimakingdoms.presentation;

import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.SettlementView;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.network.NetworkHandler;
import com.ultimakingdoms.network.OverlayPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ServerPresentationEvents {
    private static final Map<UUID, OverlayPacket> LAST_SENT = new HashMap<>();

    private ServerPresentationEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
                || player.tickCount % 10 != 0) {
            return;
        }
        KingdomsService service;
        try {
            service = UltimaKingdomsApi.get(player.getServer());
        } catch (IllegalStateException ignored) {
            return;
        }
        Optional<SettlementView> current = service.getSettlementAt(player.serverLevel(), player.blockPosition());
        current.ifPresent(settlement -> com.ultimakingdoms.knowledge.SettlementKnowledge.get(player.getServer())
                .discover(player.getUUID(), settlement.id()));
        OverlayPacket packet = current.flatMap(settlement -> service.getKingdom(settlement.kingdomId())
                        .map(kingdom -> new OverlayPacket(Optional.of(player.level().dimension().location()),
                                Optional.of(SettlementSummary.from(settlement)),
                                Optional.of(KingdomSummary.from(kingdom)))))
                .orElseGet(() -> OverlayPacket.clear(player.level().dimension().location()));
        OverlayPacket previous = LAST_SENT.get(player.getUUID());
        if (packet.equals(previous) || previous == null && packet.settlement().isEmpty()) return;
        LAST_SENT.put(player.getUUID(), packet);
        NetworkHandler.sendOverlay(player, packet);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
        NetworkHandler.forgetPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_SENT.clear();
    }
}
