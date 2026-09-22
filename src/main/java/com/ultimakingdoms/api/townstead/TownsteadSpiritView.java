package com.ultimakingdoms.api.townstead;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record TownsteadSpiritView(
        int villageId, Map<String, Integer> perSpirit, int total, int contributingBuildings,
        int tier, String classification, Optional<String> primaryId, Optional<String> secondaryId
) {
    public TownsteadSpiritView {
        if (villageId < 0 || total < 0 || contributingBuildings < 0 || tier < 0) {
            throw new IllegalArgumentException("Invalid Townstead spirit snapshot");
        }
        perSpirit = Map.copyOf(Objects.requireNonNull(perSpirit, "perSpirit"));
        Objects.requireNonNull(classification, "classification");
        primaryId = Objects.requireNonNull(primaryId, "primaryId");
        secondaryId = Objects.requireNonNull(secondaryId, "secondaryId");
    }
}
