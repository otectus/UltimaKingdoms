package com.ultimakingdoms.api.worldcontext;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** Server-owned, discovery-filtered world context. Locations returned here are safe for this viewer. */
public final class WorldContextApi {
    public record Site(UUID id, ResourceLocation dimension, BlockPos position, String role,
                       ResourceLocation provenance, String evidence, long discoveredAt,
                       String recognizedKingdom, String controller, boolean contested) {
        public Site { position = position.immutable(); }
    }
    public record Checkpoint(ResourceLocation dimension, BlockPos position, String label) {
        public Checkpoint { position = position.immutable(); }
    }
    public record Route(UUID id, UUID fromInstitution, UUID toInstitution, List<Checkpoint> checkpoints,
                        int nextCheckpoint, boolean active, boolean accessible, String status) {
        public Route { checkpoints = List.copyOf(checkpoints); }
    }
    public record Encounter(UUID receipt, ResourceLocation encounter, UUID entity, UUID site,
                            int contribution, int repeat, long completedAt) { }
    public record MapPoint(String key, ResourceLocation dimension, BlockPos position, String label,
                           String kind, boolean approximate, boolean lastKnown) {
        public MapPoint { position = position.immutable(); }
    }
    public record Action(boolean allowed, String reason, Optional<UUID> route) {
        public static Action deny(String reason) { return new Action(false, reason, Optional.empty()); }
    }
    public interface Provider {
        List<Site> sites(ServerPlayer viewer, int offset, int limit);
        List<Route> routes(ServerPlayer viewer, int offset, int limit);
        List<Encounter> encounters(ServerPlayer viewer, int offset, int limit);
        List<MapPoint> mapPoints(ServerPlayer viewer, int limit);
        Action beginRoute(ServerPlayer player, UUID route);
        Action requestNeutralResourceAccess(ServerPlayer player, UUID site, ResourceLocation commodity);
        long revision();
    }
    private static final Map<MinecraftServer, Provider> PROVIDERS = new WeakHashMap<>();
    private WorldContextApi() { }
    public static Optional<Provider> get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("World context requires server thread");
        return Optional.ofNullable(PROVIDERS.get(server));
    }
    public static void attach(MinecraftServer server, Provider provider) {
        if (!server.isSameThread() || PROVIDERS.putIfAbsent(server, provider) != null)
            throw new IllegalStateException("World context already attached or wrong thread");
    }
    public static void detach(MinecraftServer server) { PROVIDERS.remove(server); }
}
