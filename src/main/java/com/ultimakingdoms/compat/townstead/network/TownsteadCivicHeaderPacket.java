package com.ultimakingdoms.compat.townstead.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record TownsteadCivicHeaderPacket(
        long requestId, ResourceLocation dimension, int villageId,
        Optional<Header> header
) {
    public TownsteadCivicHeaderPacket {
        if (requestId < 1 || villageId < 0) throw new IllegalArgumentException("Invalid civic header response");
        Objects.requireNonNull(dimension, "dimension");
        header = Objects.requireNonNull(header, "header");
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(requestId);
        buffer.writeResourceLocation(dimension);
        buffer.writeVarInt(villageId);
        buffer.writeBoolean(header.isPresent());
        header.ifPresent(value -> value.encode(buffer));
    }

    public static TownsteadCivicHeaderPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readVarLong();
        ResourceLocation dimension = buffer.readResourceLocation();
        int villageId = buffer.readVarInt();
        return new TownsteadCivicHeaderPacket(requestId, dimension, villageId,
                buffer.readBoolean() ? Optional.of(Header.decode(buffer)) : Optional.empty());
    }

    public record Header(String settlementName, ResourceLocation kingdomId, String kingdomNameKey,
                         ResourceLocation heraldryIcon, int uiColor, String standingTier) {
        public Header {
            if (settlementName == null || settlementName.isBlank() || settlementName.length() > 128
                    || kingdomNameKey == null || kingdomNameKey.isBlank() || kingdomNameKey.length() > 160
                    || standingTier == null || standingTier.isBlank() || standingTier.length() > 64) {
                throw new IllegalArgumentException("Invalid civic header text");
            }
            Objects.requireNonNull(kingdomId, "kingdomId");
            Objects.requireNonNull(heraldryIcon, "heraldryIcon");
        }

        private void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(settlementName, 128);
            buffer.writeResourceLocation(kingdomId);
            buffer.writeUtf(kingdomNameKey, 160);
            buffer.writeResourceLocation(heraldryIcon);
            buffer.writeInt(uiColor);
            buffer.writeUtf(standingTier, 64);
        }

        private static Header decode(FriendlyByteBuf buffer) {
            return new Header(buffer.readUtf(128), buffer.readResourceLocation(), buffer.readUtf(160),
                    buffer.readResourceLocation(), buffer.readInt(), buffer.readUtf(64));
        }
    }
}
