package com.ultimakingdoms.api.warfare;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Immutable political context. Civilian identity and native physical control remain distinct. */
public final class WarfareApi {
    public record Control(UUID settlement, String civicKingdom, String recognizedKingdom, String nativeController,
                          String autonomy, boolean contested, long sequence, String availability) { }
    public interface Provider {
        /** Trusted server policy read, not permission to send locations or intelligence. */
        Optional<Control> control(UUID settlement);
        /** Only control facts the viewer may currently receive. */
        Optional<Control> control(ServerPlayer viewer, UUID settlement);
        boolean reliefEligible(ServerPlayer player, UUID settlement);
        boolean safeConduct(ServerPlayer player, UUID settlement);
    }
    private static final Map<MinecraftServer, Provider> PROVIDERS = new WeakHashMap<>();
    private WarfareApi() { }
    public static Optional<Provider> get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Warfare API requires server thread");
        return Optional.ofNullable(PROVIDERS.get(server));
    }
    public static void attach(MinecraftServer server, Provider provider) {
        if (!server.isSameThread() || PROVIDERS.putIfAbsent(server, provider) != null) throw new IllegalStateException("Warfare already attached or wrong thread");
    }
    public static void detach(MinecraftServer server) { PROVIDERS.remove(server); }
}
