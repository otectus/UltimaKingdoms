package com.ultimakingdoms.api;

import net.minecraft.server.MinecraftServer;

public final class ApiBootstrap {
    private ApiBootstrap() {
    }

    public static void attach(MinecraftServer server, KingdomsService service) {
        UltimaKingdomsApi.attach(server, service);
    }

    public static void detach(MinecraftServer server, KingdomsService service) {
        UltimaKingdomsApi.detach(server, service);
    }
}
