package com.ultimakingdoms.api.politics;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;
import java.util.Objects;

/** Version 1 server-side post-commit fact. This is not a narrative-delivery acknowledgment. */
public final class PoliticalCommittedEvent extends Event {
    private final MinecraftServer server;
    private final Politics.Notice notice;
    public PoliticalCommittedEvent(MinecraftServer server, Politics.Notice notice) {
        this.server = Objects.requireNonNull(server); this.notice = Objects.requireNonNull(notice);
    }
    public int schemaVersion() { return 1; }
    public MinecraftServer server() { return server; }
    public Politics.Notice notice() { return notice; }
}
