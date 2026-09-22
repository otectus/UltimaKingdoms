package com.ultimakingdoms.evolution;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;

public final class EvolutionRuntime {
    private static final Map<MinecraftServer, EvolutionService> SERVICES = new WeakHashMap<>();
    public static EvolutionService get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread");
        return SERVICES.computeIfAbsent(server, EvolutionService::new);
    }
    public static boolean dramaEnabled(MinecraftServer server) {
        var data = EvolutionSavedData.get(server); var state = data.snapshot();
        return data.writable() && state.enabled && state.drama;
    }
    public static long activeScenarioCount(MinecraftServer server) {
        return EvolutionSavedData.get(server).snapshot().scenarios.values().stream().filter(EvolutionState.Scenario::pending).count();
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer().getTickCount() % 200 == 0) get(event.getServer()).tick();
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { SERVICES.remove(event.getServer()); }
    private EvolutionRuntime() { }
}
