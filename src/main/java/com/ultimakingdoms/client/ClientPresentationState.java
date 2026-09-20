package com.ultimakingdoms.client;

import com.ultimakingdoms.network.LedgerPagePacket;
import com.ultimakingdoms.network.OverlayPacket;
import com.ultimakingdoms.presentation.KingdomSummary;
import com.ultimakingdoms.presentation.PresentationConfig;
import com.ultimakingdoms.presentation.SettlementSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

public final class ClientPresentationState {
    private static LedgerPagePacket ledgerPage;
    private static OverlayPacket currentOverlay = OverlayPacket.empty();
    private static ResourceLocation clientDimension;
    private static UUID lastSettlement;
    private static ResourceLocation lastKingdom;
    private static int overlayTicks;
    private static int overlayDuration;

    private ClientPresentationState() {
    }

    public static void receiveLedgerPage(LedgerPagePacket page) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof VillageLedgerScreen ledgerScreen) {
            if (ledgerScreen.receive(page)) ledgerPage = page;
        }
    }

    public static void receiveOverlay(OverlayPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation actualDimension = minecraft.level == null
                ? null : minecraft.level.dimension().location();
        if (packet.dimension().isPresent() && !packet.dimension().orElseThrow().equals(actualDimension)) {
            return;
        }
        if (!java.util.Objects.equals(clientDimension, actualDimension)) {
            clearTransientState();
            clientDimension = actualDimension;
        }
        if (packet.settlement().isEmpty()) {
            currentOverlay = OverlayPacket.empty();
            lastSettlement = null;
            return;
        }
        SettlementSummary settlement = packet.settlement().orElseThrow();
        KingdomSummary kingdom = packet.kingdom().orElseThrow();
        boolean changedSettlement = !settlement.id().equals(lastSettlement);
        boolean changedKingdom = !kingdom.id().equals(lastKingdom);
        currentOverlay = packet;
        lastSettlement = settlement.id();
        lastKingdom = kingdom.id();
        if (PresentationConfig.SHOW_SETTLEMENT_OVERLAY.get() && changedSettlement
                && (!PresentationConfig.KINGDOM_BORDERS_ONLY.get() || changedKingdom)) {
            overlayTicks = PresentationConfig.OVERLAY_DURATION_TICKS.get();
            overlayDuration = overlayTicks;
        }
    }

    public static void openLedger() {
        Minecraft.getInstance().setScreen(new VillageLedgerScreen(ledgerPage));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation dimension = minecraft.level == null ? null : minecraft.level.dimension().location();
        if (clientDimension == null) {
            clientDimension = dimension;
        } else if (!java.util.Objects.equals(clientDimension, dimension)) {
            clearTransientState();
            clientDimension = dimension;
        } else if (!minecraft.isPaused() && overlayTicks > 0) {
            overlayTicks--;
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (overlayTicks <= 0 || currentOverlay.settlement().isEmpty()
                || !PresentationConfig.SHOW_SETTLEMENT_OVERLAY.get()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null) return;

        SettlementSummary settlement = currentOverlay.settlement().orElseThrow();
        KingdomSummary kingdom = currentOverlay.kingdom().orElseThrow();
        SettlementOverlayRenderer.render(event.getGuiGraphics(), minecraft.font, settlement, kingdom,
                event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight(),
                PresentationConfig.OVERLAY_Y.get(), overlayTicks, overlayDuration, event.getPartialTick());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
    }

    private static void clearTransientState() {
        currentOverlay = OverlayPacket.empty();
        lastSettlement = null;
        lastKingdom = null;
        overlayTicks = 0;
        ledgerPage = null;
    }

    private static void clearAll() {
        clearTransientState();
        clientDimension = null;
    }
}
