package com.ultimakingdoms.api.event;

import com.ultimakingdoms.api.CivicIdentityView;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;
import java.util.UUID;

public final class CivicIdentityChangedEvent extends Event {
    private final UUID entityId;
    private final CivicIdentityView oldIdentity;
    private final CivicIdentityView newIdentity;

    public CivicIdentityChangedEvent(UUID entityId, CivicIdentityView oldIdentity, CivicIdentityView newIdentity) {
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.oldIdentity = Objects.requireNonNull(oldIdentity, "oldIdentity");
        this.newIdentity = Objects.requireNonNull(newIdentity, "newIdentity");
    }

    public UUID entityId() { return entityId; }

    public CivicIdentityView oldIdentity() { return oldIdentity; }

    public CivicIdentityView newIdentity() { return newIdentity; }
}
