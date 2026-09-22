package com.ultimakingdoms.api.politics;

import net.minecraft.server.MinecraftServer;
import java.util.*;

public final class UltimaPoliticsApi {
    private static final Map<MinecraftServer, PoliticalService> SERVICES = Collections.synchronizedMap(new WeakHashMap<>());
    private UltimaPoliticsApi() { }
    public static PoliticalService get(MinecraftServer server) {
        return Objects.requireNonNull(SERVICES.get(server), "Political service is not ready");
    }
    public static void attach(MinecraftServer server, PoliticalService service) {
        if (SERVICES.putIfAbsent(server, service) != null) throw new IllegalStateException("Politics already attached");
    }
    public static void detach(MinecraftServer server) { SERVICES.remove(server); }
}
