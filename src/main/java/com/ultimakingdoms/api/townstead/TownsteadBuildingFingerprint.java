package com.ultimakingdoms.api.townstead;

import java.util.Objects;
import java.util.UUID;

public record TownsteadBuildingFingerprint(UUID bindingId, int buildingId, String type, int size) {
    public TownsteadBuildingFingerprint {
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(type, "type");
    }
}
