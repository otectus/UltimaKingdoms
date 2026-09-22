package com.ultimakingdoms.api.factions.organization;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrganizationPlayerSnapshot(UUID playerId, long revision,
                                         List<OrganizationMembershipSnapshot> memberships) {
    public OrganizationPlayerSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        memberships = List.copyOf(memberships);
    }
}
