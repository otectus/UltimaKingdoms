package com.ultimakingdoms.api.factions;

import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class UltimaFactionsApi {
    private static final Map<MinecraftServer, UltimaFactionsService> SERVICES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private UltimaFactionsApi() {
    }

    public static UltimaFactionsService get(MinecraftServer server) {
        UltimaFactionsService service = SERVICES.get(Objects.requireNonNull(server, "server"));
        if (service == null) throw new IllegalStateException("Ultima Factions is not ready for this server");
        return service;
    }

    public static void attach(MinecraftServer server, UltimaFactionsService service) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(service, "service");
        UltimaFactionsService previous = SERVICES.putIfAbsent(server, service);
        if (previous != null && previous != service) {
            throw new IllegalStateException("Ultima Factions is already attached to this server");
        }
    }

    public static void detach(MinecraftServer server, UltimaFactionsService service) {
        SERVICES.remove(Objects.requireNonNull(server, "server"), Objects.requireNonNull(service, "service"));
    }
}
