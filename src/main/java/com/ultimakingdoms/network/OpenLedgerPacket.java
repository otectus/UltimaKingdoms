package com.ultimakingdoms.network;

import net.minecraft.network.FriendlyByteBuf;

public record OpenLedgerPacket() {
    public static OpenLedgerPacket decode(FriendlyByteBuf ignored) {
        return new OpenLedgerPacket();
    }

    public void encode(FriendlyByteBuf ignored) {
    }
}
