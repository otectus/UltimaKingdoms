package com.ultimakingdoms.api.event;

import com.ultimakingdoms.api.SettlementView;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;
import java.util.Objects;

/** Post-commit notification; source is the retired snapshot and target is canonical. */
public final class SettlementMergedEvent extends Event {
    private final MinecraftServer server;
    private final SettlementView source;
    private final SettlementView target;
    public SettlementMergedEvent(MinecraftServer server, SettlementView source, SettlementView target) {
        this.server = Objects.requireNonNull(server);
        this.source = Objects.requireNonNull(source);
        this.target = Objects.requireNonNull(target);
    }
    public MinecraftServer server() { return server; }
    public SettlementView source() { return source; }
    public SettlementView target() { return target; }
}
