package com.ultimakingdoms.api.event;

import net.minecraftforge.eventbus.api.Event;

public final class KingdomDefinitionsReloadedEvent extends Event {
    private final long revision;

    public KingdomDefinitionsReloadedEvent(long revision) {
        this.revision = revision;
    }

    public long revision() {
        return revision;
    }
}
