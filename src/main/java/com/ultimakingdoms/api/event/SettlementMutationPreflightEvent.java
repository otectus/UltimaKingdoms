package com.ultimakingdoms.api.event;

import com.ultimakingdoms.api.SettlementView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import java.util.Objects;
import java.util.Optional;

/** Server-thread validation only. Listeners must not mutate settlements during preflight. */
@Cancelable
public final class SettlementMutationPreflightEvent extends Event {
    private final MinecraftServer server;
    private final SettlementView source;
    private final SettlementView target;
    private final ResourceLocation kingdom;
    private String rejection = "Settlement mutation rejected by a provider";
    public SettlementMutationPreflightEvent(MinecraftServer server, SettlementView source,
                                            SettlementView target, ResourceLocation kingdom) {
        this.server = Objects.requireNonNull(server);
        this.source = Objects.requireNonNull(source);
        this.target = target;
        this.kingdom = Objects.requireNonNull(kingdom);
    }
    public MinecraftServer server() { return server; }
    public SettlementView source() { return source; }
    public Optional<SettlementView> mergeTarget() { return Optional.ofNullable(target); }
    public ResourceLocation destinationKingdom() { return kingdom; }
    public String rejection() { return rejection; }
    public void reject(String reason) { rejection = Objects.requireNonNull(reason); setCanceled(true); }
}
