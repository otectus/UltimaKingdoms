package com.ultimakingdoms.compat.townstead.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record TownsteadCivicRequestPacket(long requestId, ResourceLocation dimension, int villageId) {
    public TownsteadCivicRequestPacket {
        if (requestId < 1 || villageId < 0) throw new IllegalArgumentException("Invalid civic header request");
        Objects.requireNonNull(dimension, "dimension");
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(requestId);
        buffer.writeResourceLocation(dimension);
        buffer.writeVarInt(villageId);
    }

    public static TownsteadCivicRequestPacket decode(FriendlyByteBuf buffer) {
        return new TownsteadCivicRequestPacket(buffer.readVarLong(), buffer.readResourceLocation(), buffer.readVarInt());
    }
}
