package com.ultimakingdoms.api.event;

import com.ultimakingdoms.api.CivicIdentityView;
import com.ultimakingdoms.api.SettlementView;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;
import java.util.UUID;

public final class ResidentLeftEvent extends Event {
    private final UUID entityId;
    private final SettlementView settlement;
    private final CivicIdentityView identity;

    public ResidentLeftEvent(UUID entityId, SettlementView settlement, CivicIdentityView identity) {
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    public UUID entityId() { return entityId; }

    public SettlementView settlement() { return settlement; }

    public CivicIdentityView identity() { return identity; }
}
