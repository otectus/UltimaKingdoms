package com.ultimakingdoms.api.event;

import com.ultimakingdoms.api.ChangeReason;
import com.ultimakingdoms.api.SettlementView;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class SettlementKingdomChangedEvent extends Event {
    private final SettlementView settlement;
    private final ResourceLocation oldKingdom;
    private final ResourceLocation newKingdom;
    private final ChangeReason reason;

    public SettlementKingdomChangedEvent(SettlementView settlement, ResourceLocation oldKingdom,
                                         ResourceLocation newKingdom, ChangeReason reason) {
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.oldKingdom = Objects.requireNonNull(oldKingdom, "oldKingdom");
        this.newKingdom = Objects.requireNonNull(newKingdom, "newKingdom");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public SettlementView settlement() {
        return settlement;
    }

    public ResourceLocation oldKingdom() {
        return oldKingdom;
    }

    public ResourceLocation newKingdom() {
        return newKingdom;
    }

    public ChangeReason reason() {
        return reason;
    }
}
