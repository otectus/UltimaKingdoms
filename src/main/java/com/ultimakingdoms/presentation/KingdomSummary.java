package com.ultimakingdoms.presentation;

import com.ultimakingdoms.api.KingdomView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record KingdomSummary(ResourceLocation id, String translationKey, ResourceLocation heraldryIcon, int uiColor) {
    public KingdomSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(translationKey, "translationKey");
        Objects.requireNonNull(heraldryIcon, "heraldryIcon");
    }

    public static KingdomSummary from(KingdomView view) {
        return new KingdomSummary(view.id(), view.translationKey(), view.heraldryIcon(), view.uiColor());
    }

    public static KingdomSummary decode(FriendlyByteBuf buffer) {
        return new KingdomSummary(buffer.readResourceLocation(), buffer.readUtf(128),
                buffer.readResourceLocation(), buffer.readInt());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(id);
        buffer.writeUtf(translationKey, 128);
        buffer.writeResourceLocation(heraldryIcon);
        buffer.writeInt(uiColor);
    }
}
