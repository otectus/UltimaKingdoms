package com.ultimakingdoms.api.event;

import com.ultimakingdoms.api.SettlementView;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class SettlementCreatedEvent extends Event {
    private final SettlementView settlement;

    public SettlementCreatedEvent(SettlementView settlement) {
        this.settlement = Objects.requireNonNull(settlement, "settlement");
    }

    public SettlementView settlement() {
        return settlement;
    }
}
