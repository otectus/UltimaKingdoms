package com.ultimakingdoms.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record LedgerRequestPacket(long requestId, long expectedRegistryRevision,
                                  Optional<ResourceLocation> kingdomId, int offset) {
    public LedgerRequestPacket {
        if (requestId < 1 || expectedRegistryRevision < 0) {
            throw new IllegalArgumentException("Invalid ledger request metadata");
        }
    }

    public static LedgerRequestPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readVarLong();
        long expectedRevision = buffer.readVarLong();
        return new LedgerRequestPacket(requestId, expectedRevision, buffer.readBoolean()
                ? Optional.of(buffer.readResourceLocation()) : Optional.empty(), buffer.readVarInt());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(requestId);
        buffer.writeVarLong(expectedRegistryRevision);
        buffer.writeBoolean(kingdomId.isPresent());
        kingdomId.ifPresent(buffer::writeResourceLocation);
        buffer.writeVarInt(offset);
    }
}
