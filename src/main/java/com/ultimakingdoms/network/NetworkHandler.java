package com.ultimakingdoms.network;

import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.presentation.KingdomSummary;
import com.ultimakingdoms.presentation.SettlementSummary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class NetworkHandler {
    private static final String PROTOCOL = "2";
    private static final int PAGE_SIZE_WITH_SENTINEL = 21;
    private static final int MAX_OFFSET = 1_000_000;
    private static final int REQUEST_COOLDOWN_TICKS = 4;
    private static final AtomicLong CLIENT_REQUEST_IDS = new AtomicLong();
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(UltimaKingdomsApi.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();
    private static final Map<UUID, Integer> LAST_LEDGER_REQUEST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_RETRY_RESPONSE = new ConcurrentHashMap<>();

    private static volatile Consumer<LedgerPagePacket> ledgerPageReceiver = ignored -> { };
    private static volatile Consumer<OverlayPacket> overlayReceiver = ignored -> { };
    private static volatile Runnable openLedgerReceiver = () -> { };
    private static boolean initialized;

    private NetworkHandler() {
    }

    public static synchronized void init() {
        if (initialized) return;
        int id = 0;
        CHANNEL.messageBuilder(LedgerRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(LedgerRequestPacket::encode)
                .decoder(LedgerRequestPacket::decode)
                .consumerMainThread((packet, context) -> handleLedgerRequest(packet, context.get().getSender()))
                .add();
        CHANNEL.messageBuilder(LedgerPagePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(LedgerPagePacket::encode)
                .decoder(LedgerPagePacket::decode)
                .consumerMainThread((packet, ignored) -> ledgerPageReceiver.accept(packet))
                .add();
        CHANNEL.messageBuilder(OpenLedgerPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenLedgerPacket::encode)
                .decoder(OpenLedgerPacket::decode)
                .consumerMainThread((ignoredPacket, ignoredContext) -> openLedgerReceiver.run())
                .add();
        CHANNEL.messageBuilder(OverlayPacket.class, id, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OverlayPacket::encode)
                .decoder(OverlayPacket::decode)
                .consumerMainThread((packet, ignored) -> overlayReceiver.accept(packet))
                .add();
        initialized = true;
    }

    public static void setClientReceivers(Consumer<LedgerPagePacket> ledgerPages,
                                          Consumer<OverlayPacket> overlays,
                                          Runnable openLedger) {
        ledgerPageReceiver = ledgerPages;
        overlayReceiver = overlays;
        openLedgerReceiver = openLedger;
    }

    public static long requestLedgerPage(Optional<ResourceLocation> kingdomId, int offset,
                                         long expectedRegistryRevision) {
        long requestId = CLIENT_REQUEST_IDS.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1 : previous + 1);
        CHANNEL.sendToServer(new LedgerRequestPacket(requestId, expectedRegistryRevision, kingdomId, offset));
        return requestId;
    }

    public static void openLedger(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenLedgerPacket());
    }

    public static void sendOverlay(ServerPlayer player, OverlayPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void forgetPlayer(UUID playerId) {
        LAST_LEDGER_REQUEST.remove(playerId);
        LAST_RETRY_RESPONSE.remove(playerId);
    }

    private static void handleLedgerRequest(LedgerRequestPacket packet, ServerPlayer player) {
        if (player == null) return;
        int now = player.getServer().getTickCount();
        int offset = packet.offset();
        if (offset < 0 || offset > MAX_OFFSET || offset % 20 != 0) return;
        KingdomsService service;
        try {
            service = UltimaKingdomsApi.get(player.getServer());
        } catch (IllegalStateException ignored) {
            return;
        }
        Optional<ResourceLocation> kingdom = packet.kingdomId();
        if (kingdom.isPresent() && service.getKingdom(kingdom.get()).isEmpty()) return;

        Integer previous = LAST_LEDGER_REQUEST.get(player.getUUID());
        if (previous != null && now - previous < REQUEST_COOLDOWN_TICKS) {
            int retryAfter = REQUEST_COOLDOWN_TICKS - (now - previous);
            Integer lastRetry = LAST_RETRY_RESPONSE.get(player.getUUID());
            if (lastRetry == null || lastRetry != now) {
                LAST_RETRY_RESPONSE.put(player.getUUID(), now);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new LedgerPagePacket(packet.requestId(), service.revision(), kingdom, offset,
                                false, retryAfter, List.of(), List.of()));
            }
            return;
        }
        LAST_LEDGER_REQUEST.put(player.getUUID(), now);

        long revision = service.revision();
        boolean revisionReset = packet.expectedRegistryRevision() != 0
                && packet.expectedRegistryRevision() != revision;
        int actualOffset = revisionReset ? 0 : offset;
        List<SettlementSummary> settlements = service
                .getSettlementPage(kingdom, actualOffset, PAGE_SIZE_WITH_SENTINEL)
                .stream().map(SettlementSummary::from).toList();
        List<KingdomSummary> kingdoms = service.getKingdoms().stream()
                .sorted(Comparator.comparing(view -> view.id().toString()))
                .limit(64)
                .map(KingdomSummary::from)
                .toList();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new LedgerPagePacket(packet.requestId(), revision, kingdom, actualOffset,
                        revisionReset, 0, settlements, kingdoms));
    }
}
