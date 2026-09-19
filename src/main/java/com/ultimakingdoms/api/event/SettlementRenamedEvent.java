package com.ultimakingdoms.api.event;

import com.ultimakingdoms.api.ChangeReason;
import com.ultimakingdoms.api.SettlementView;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class SettlementRenamedEvent extends Event {
    private final SettlementView settlement;
    private final String oldName;
    private final ChangeReason reason;

    public SettlementRenamedEvent(SettlementView settlement, String oldName, ChangeReason reason) {
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.oldName = Objects.requireNonNull(oldName, "oldName");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public SettlementView settlement() {
        return settlement;
    }

    public String oldName() {
        return oldName;
    }

    public ChangeReason reason() {
        return reason;
    }
}
