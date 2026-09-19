package com.ultimakingdoms.client;

import com.ultimakingdoms.network.NetworkHandler;
import net.minecraftforge.common.MinecraftForge;

public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void init() {
        NetworkHandler.setClientReceivers(ClientPresentationState::receiveLedgerPage,
                ClientPresentationState::receiveOverlay, ClientPresentationState::openLedger);
        MinecraftForge.EVENT_BUS.register(ClientPresentationState.class);
    }
}
