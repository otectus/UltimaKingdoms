package com.ultimakingdoms.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CivicIdentityHint(
        Optional<UUID> originSettlement,
        Optional<UUID> residenceSettlement,
        CivicIdentitySource source
) {
    public CivicIdentityHint {
        originSettlement = Objects.requireNonNull(originSettlement, "originSettlement");
        residenceSettlement = Objects.requireNonNull(residenceSettlement, "residenceSettlement");
        Objects.requireNonNull(source, "source");
    }
}
