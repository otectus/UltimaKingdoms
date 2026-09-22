package com.ultimakingdoms.api.gating;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.Arrays;

/** The server-owned civic or location context used to evaluate a kingdom gate. */
public enum KingdomSubject {
    GIVER_RESIDENCE("giver_residence"),
    GIVER_ORIGIN("giver_origin"),
    PLAYER_LOCATION("player_location"),
    GIVER_LOCATION("giver_location"),
    EXPLICIT_SETTLEMENT("explicit_settlement");

    public static final Codec<KingdomSubject> CODEC = Codec.STRING.comapFlatMap(
            value -> fromSerializedName(value)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown kingdom subject: " + value)),
            KingdomSubject::serializedName);

    private final String serializedName;

    KingdomSubject(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static java.util.Optional<KingdomSubject> fromSerializedName(String value) {
        return Arrays.stream(values()).filter(subject -> subject.serializedName.equals(value)).findFirst();
    }
}
