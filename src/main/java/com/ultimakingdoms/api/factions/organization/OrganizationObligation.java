package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.UUID;

/** An accepted native commission that must finish, cancel, or be explicitly novated before lifecycle closure. */
public record OrganizationObligation(UUID id, UUID player, UUID nativeInstance,
                                     ResourceLocation organization, ResourceLocation quest) {
    public OrganizationObligation {
        Objects.requireNonNull(id); Objects.requireNonNull(player); Objects.requireNonNull(nativeInstance);
        Objects.requireNonNull(organization); Objects.requireNonNull(quest);
    }
}
