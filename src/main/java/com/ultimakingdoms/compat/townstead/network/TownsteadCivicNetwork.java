package com.ultimakingdoms.compat.townstead.network;

import com.ultimakingdoms.api.KingdomView;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.McaCommunityRef;
import com.ultimakingdoms.api.SettlementView;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.factions.UltimaFactionsApi;
import com.ultimakingdoms.api.factions.UltimaFactionsService;
import com.ultimakingdoms.compat.townstead.TownsteadIntegrationConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Dedicated bounded request channel for the optional Blueprint civic header. */
public final class TownsteadCivicNetwork {
    private static final String PROTOCOL = "1";
    private static final ResourceLocation CHANNEL_ID = new ResourceLocation("ultima_kingdoms", "townstead_civic");
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder.named(CHANNEL_ID)
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static final Map<UUID, Integer> LAST_REQUEST = new ConcurrentHashMap<>();
    private static volatile Consumer<TownsteadCivicHeaderPacket> receiver = ignored -> { };
    private static boolean initialized;

    private TownsteadCivicNetwork() {
    }

    public static synchronized void init() {
        if (initialized) return;
        CHANNEL.messageBuilder(TownsteadCivicRequestPacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(TownsteadCivicRequestPacket::encode)
                .decoder(TownsteadCivicRequestPacket::decode)
                .consumerMainThread((packet, context) -> handle(packet, context.get().getSender()))
                .add();
        CHANNEL.messageBuilder(TownsteadCivicHeaderPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TownsteadCivicHeaderPacket::encode)
                .decoder(TownsteadCivicHeaderPacket::decode)
                .consumerMainThread((packet, ignored) -> receiver.accept(packet))
                .add();
        initialized = true;
    }

    public static void setClientReceiver(Consumer<TownsteadCivicHeaderPacket> clientReceiver) {
        receiver = clientReceiver == null ? ignored -> { } : clientReceiver;
    }

    public static long request(ResourceLocation dimension, int villageId) {
        long id = REQUEST_IDS.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1 : previous + 1);
        CHANNEL.sendToServer(new TownsteadCivicRequestPacket(id, dimension, villageId));
        return id;
    }

    public static void clearServerState() {
        LAST_REQUEST.clear();
    }

    private static void handle(TownsteadCivicRequestPacket packet, ServerPlayer player) {
        if (player == null || !TownsteadIntegrationConfig.ENABLE_BLUEPRINT_HEADER.get()
                || !player.level().dimension().location().equals(packet.dimension())) return;
        int now = player.getServer().getTickCount();
        Integer previous = LAST_REQUEST.put(player.getUUID(), now);
        if (previous != null && now - previous < 10) return;
        KingdomsService kingdoms;
        try {
            kingdoms = UltimaKingdomsApi.get(player.getServer());
        } catch (IllegalStateException unavailable) {
            return;
        }
        Optional<SettlementView> settlement = kingdoms.getSettlementForMcaVillage(packet.dimension(), packet.villageId());
        Optional<TownsteadCivicHeaderPacket.Header> header = settlement
                .filter(value -> authorized(player, value))
                .flatMap(value -> kingdoms.getKingdom(value.kingdomId())
                        .filter(KingdomView::defined)
                        .map(kingdom -> header(player, value, kingdom)));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new TownsteadCivicHeaderPacket(packet.requestId(), packet.dimension(), packet.villageId(), header));
    }

    private static boolean authorized(ServerPlayer player, SettlementView settlement) {
        if (!settlement.dimension().equals(player.level().dimension())) return false;
        long range = TownsteadIntegrationConfig.BLUEPRINT_REQUEST_RANGE.get();
        return player.blockPosition().distSqr(settlement.anchor()) <= range * range;
    }

    private static TownsteadCivicHeaderPacket.Header header(ServerPlayer player, SettlementView settlement,
                                                             KingdomView kingdom) {
        String standing = "neutral";
        try {
            UltimaFactionsService factions = UltimaFactionsApi.get(player.getServer());
            standing = factions.getStanding(player.getUUID(), kingdom.id())
                    .map(value -> value.tierId()).orElse("neutral");
            Optional<McaCommunityRef> community = Optional.ofNullable(
                            settlement.externalRefs().get(McaCommunityRef.EXTERNAL_REF_NAMESPACE))
                    .flatMap(McaCommunityRef::parse);
            if (community.isPresent()) {
                var local = factions.getLocalStanding(player.getUUID(), community.get());
                if (local.isPresent()) standing = standing + " / local " + local.getAsInt();
            }
        } catch (RuntimeException ignored) {
            // Faction and local standing are optional presentation inputs.
        }
        return new TownsteadCivicHeaderPacket.Header(settlement.displayName(), kingdom.id(),
                kingdom.translationKey(), kingdom.heraldryIcon(), kingdom.uiColor(), standing);
    }
}
