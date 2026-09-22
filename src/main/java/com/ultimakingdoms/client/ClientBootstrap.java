package com.ultimakingdoms.client;

import com.ultimakingdoms.network.NetworkHandler;
import com.ultimakingdoms.client.townstead.TownsteadBlueprintHeader;
import net.minecraftforge.common.MinecraftForge;

public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void init() {
        BookGuideClient.init();
        InteractionClient.init();
        NetworkHandler.setClientReceivers(ClientPresentationState::receiveLedgerPage,
                ClientPresentationState::receiveOverlay, ClientPresentationState::openLedger);
        TownsteadBlueprintHeader.init();
        com.ultimakingdoms.civic.CivicNetwork.receiver(GuildScreen::receive);
        com.ultimakingdoms.politics.PoliticalNetwork.receiver(com.ultimakingdoms.client.politics.KingdomScreen::receive);
        com.ultimakingdoms.warfare.WarfareNetwork.receiver(WarRoomScreen::receive);
        MinecraftForge.EVENT_BUS.register(ClientPresentationState.class);
    }
}
