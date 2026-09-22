package com.ultimakingdoms.api.factions.event;

import com.ultimakingdoms.api.factions.FactionStandingRequest;
import com.ultimakingdoms.api.factions.FactionStandingResult;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

public final class FactionStandingChangedEvent extends Event {
    private final FactionStandingResult result;
    private final FactionStandingRequest request;

    public FactionStandingChangedEvent(FactionStandingResult result, FactionStandingRequest request) {
        this.result = Objects.requireNonNull(result, "result");
        this.request = Objects.requireNonNull(request, "request");
    }

    public FactionStandingResult result() {
        return result;
    }

    public FactionStandingRequest request() {
        return request;
    }
}
