package com.ultimakingdoms.core;

import com.ultimakingdoms.api.CivicIdentitySource;
import com.ultimakingdoms.api.CivicIdentityView;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CivicIdentitySnapshot(
        Optional<UUID> originSettlement,
        Optional<ResourceLocation> originKingdom,
        Optional<UUID> residenceSettlement,
        Optional<ResourceLocation> residenceKingdom,
        CivicIdentitySource source,
        long lastResidenceChange
) implements CivicIdentityView {
    public CivicIdentitySnapshot {
        originSettlement = Objects.requireNonNull(originSettlement, "originSettlement");
        originKingdom = Objects.requireNonNull(originKingdom, "originKingdom");
        residenceSettlement = Objects.requireNonNull(residenceSettlement, "residenceSettlement");
        residenceKingdom = Objects.requireNonNull(residenceKingdom, "residenceKingdom");
        Objects.requireNonNull(source, "source");
    }

    public static CivicIdentitySnapshot empty() {
        return new CivicIdentitySnapshot(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                CivicIdentitySource.AUTOMATIC, 0L);
    }
}
