package com.ultimakingdoms.api.townstead;

import java.util.Objects;
import java.util.Optional;

public record BuildingRecoveryResult(
        BuildingRecoveryStatus status,
        Optional<BoundCivicBuilding> binding,
        Optional<TownsteadBuildingView> building,
        String reason
) {
    public BuildingRecoveryResult {
        Objects.requireNonNull(status, "status");
        binding = Objects.requireNonNull(binding, "binding");
        building = Objects.requireNonNull(building, "building");
        Objects.requireNonNull(reason, "reason");
    }
}
