package com.ultimakingdoms.evolution.drama;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Server lifecycle boundary for the optional authored drama engine. */
public final class DramaRuntime {
    private static final Map<MinecraftServer, DramaService> SERVICES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicBoolean COMMANDS = new AtomicBoolean();
    private DramaRuntime() { }
    public static PreparableReloadListener reloadListener() {
        if (COMMANDS.compareAndSet(false, true)) net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(DramaCommands.class);
        return DramaDefinitions.INSTANCE;
    }
    public static void commitPending() { DramaDefinitions.INSTANCE.commitPending(); }
    public static DramaService get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Drama requires server thread");
        return SERVICES.computeIfAbsent(server, value -> new DramaService(value, DramaDefinitions.INSTANCE));
    }
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 200 == 0) get(server).tick();
    }
    public static void clear(MinecraftServer server) { SERVICES.remove(server); }
    public static void clear() { SERVICES.clear(); DramaDefinitions.INSTANCE.clear(); }
}
