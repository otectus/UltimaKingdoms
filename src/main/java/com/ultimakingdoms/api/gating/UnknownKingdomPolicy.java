package com.ultimakingdoms.api.gating;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.Arrays;

/** Result to use when a gate's subject cannot be resolved to a currently defined kingdom. */
public enum UnknownKingdomPolicy {
    DENY("deny"),
    ALLOW("allow");

    public static final Codec<UnknownKingdomPolicy> CODEC = Codec.STRING.comapFlatMap(
            value -> fromSerializedName(value)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown kingdom fallback policy: " + value)),
            UnknownKingdomPolicy::serializedName);

    private final String serializedName;

    UnknownKingdomPolicy(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static java.util.Optional<UnknownKingdomPolicy> fromSerializedName(String value) {
        return Arrays.stream(values()).filter(policy -> policy.serializedName.equals(value)).findFirst();
    }
}
