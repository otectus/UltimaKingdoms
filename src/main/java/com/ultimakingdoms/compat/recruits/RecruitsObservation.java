package com.ultimakingdoms.compat.recruits;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;


/** Optional observation only. Never enumerates claims, loads chunks, or writes provider state. */
public final class RecruitsObservation {
    public enum Status { ABSENT, UNSUPPORTED, NOT_READY, AVAILABLE, FAILED, DIMENSION_UNSUPPORTED, OWNER_UNAVAILABLE }
    public record View(Status status, String version, String claimId, String ownerId, boolean underSiege) { }
    private RecruitsObservation() { }

    public static View here(ServerPlayer player) {
        if (!player.getServer().isSameThread()) throw new IllegalStateException("Military observations require the server thread");
        if (!player.level().dimension().equals(Level.OVERWORLD)) return empty(Status.DIMENSION_UNSUPPORTED, "");
        return lookup(player.getServer(), ChunkPos.class, player.chunkPosition());
    }

    /** Registered identity lookup only; never enumerates native claims or requests a chunk. */
    public static View claim(net.minecraft.server.MinecraftServer server, java.util.UUID id) {
        return lookup(server, java.util.UUID.class, id);
    }
    public static View at(net.minecraft.server.MinecraftServer server, ChunkPos chunk) {
        return lookup(server, ChunkPos.class, chunk);
    }

    private static View lookup(net.minecraft.server.MinecraftServer server, Class<?> keyType, Object key) {
        if (!server.isSameThread()) throw new IllegalStateException("Military observations require the server thread");
        var mod = ModList.get().getModContainerById("recruits");
        if (mod.isEmpty()) return empty(Status.ABSENT, "");
        String version = mod.get().getModInfo().getVersion().toString();
        // Audited ABI only; a similarly named API in a newer jar is not sufficient evidence.
        if (!version.equals("1.15.2")) return empty(Status.UNSUPPORTED, version);
        try {
            Class<?> events = Class.forName("com.talhanation.recruits.ClaimEvents");
            Object manager = events.getField("recruitsClaimManager").get(null);
            if (manager == null) return empty(Status.NOT_READY, version);
            Object claim = manager.getClass().getMethod("getClaim", keyType).invoke(manager, key);
            if (claim == null) return empty(Status.AVAILABLE, version);
            String id = String.valueOf(claim.getClass().getMethod("getUUID").invoke(claim));
            String owner = String.valueOf(claim.getClass().getMethod("getOwnerFactionStringID").invoke(claim));
            Object factions = Class.forName("com.talhanation.recruits.FactionEvents").getField("recruitsFactionManager").get(null);
            if (factions == null) return empty(Status.NOT_READY, version);
            if (factions.getClass().getMethod("getFactionByStringID", String.class).invoke(factions, owner) == null)
                return empty(Status.OWNER_UNAVAILABLE, version);
            boolean siege = claim.getClass().getField("isUnderSiege").getBoolean(claim);
            return new View(Status.AVAILABLE, version, bounded(id), bounded(owner), siege);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return empty(Status.FAILED, version);
        }
    }

    private static String bounded(String value) {
        if (value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid native identifier");
        return value;
    }
    private static View empty(Status status, String version) { return new View(status, version, "", "", false); }
}
