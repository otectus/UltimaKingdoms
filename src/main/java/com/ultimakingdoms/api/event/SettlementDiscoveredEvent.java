package com.ultimakingdoms.api.event;

import com.ultimakingdoms.api.SettlementView;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class SettlementDiscoveredEvent extends Event {
    private final SettlementView settlement;
    private final ResourceLocation detector;

    public SettlementDiscoveredEvent(SettlementView settlement, ResourceLocation detector) {
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    public SettlementView settlement() {
        return settlement;
    }

    public ResourceLocation detector() {
        return detector;
    }
}
