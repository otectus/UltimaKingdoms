package com.ultimakingdoms.api.factions.organization;

import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Additive public facade for non-sovereign organizations. */
public final class OrganizationApi {
    private static final Map<MinecraftServer, OrganizationService> SERVICES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private OrganizationApi() {
    }

    public static OrganizationService get(MinecraftServer server) {
        OrganizationService service = SERVICES.get(Objects.requireNonNull(server, "server"));
        if (service == null) throw new IllegalStateException("Ultima organizations are not ready for this server");
        return service;
    }

    public static void attach(MinecraftServer server, OrganizationService service) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(service, "service");
        OrganizationService previous = SERVICES.putIfAbsent(server, service);
        if (previous != null && previous != service) {
            throw new IllegalStateException("Ultima organizations are already attached to this server");
        }
    }

    public static void detach(MinecraftServer server, OrganizationService service) {
        SERVICES.remove(Objects.requireNonNull(server, "server"), Objects.requireNonNull(service, "service"));
    }
}
