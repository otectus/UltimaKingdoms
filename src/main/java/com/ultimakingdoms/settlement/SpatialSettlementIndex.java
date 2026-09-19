package com.ultimakingdoms.settlement;

import com.ultimakingdoms.api.SettlementBounds;
import com.ultimakingdoms.core.SettlementSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SpatialSettlementIndex {
    private final Map<DimensionChunk, Set<UUID>> chunks = new HashMap<>();

    public void rebuild(Collection<SettlementSnapshot> settlements) {
        chunks.clear();
        settlements.forEach(this::add);
    }

    public void add(SettlementSnapshot settlement) {
        visit(settlement.dimension(), settlement.bounds(), key ->
                chunks.computeIfAbsent(key, ignored -> new HashSet<>()).add(settlement.id()));
    }

    public void remove(SettlementSnapshot settlement) {
        visit(settlement.dimension(), settlement.bounds(), key -> {
            Set<UUID> ids = chunks.get(key);
            if (ids != null) {
                ids.remove(settlement.id());
                if (ids.isEmpty()) chunks.remove(key);
            }
        });
    }

    public Set<UUID> at(ResourceKey<Level> dimension, BlockPos pos) {
        Set<UUID> ids = chunks.get(new DimensionChunk(dimension, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)));
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    private static void visit(ResourceKey<Level> dimension, SettlementBounds bounds,
                              java.util.function.Consumer<DimensionChunk> consumer) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                consumer.accept(new DimensionChunk(dimension, ChunkPos.asLong(x, z)));
            }
        }
    }

    private record DimensionChunk(ResourceKey<Level> dimension, long chunk) {
    }
}
