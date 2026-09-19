package com.ultimakingdoms.settlement;

import com.ultimakingdoms.api.DetectionSource;
import com.ultimakingdoms.api.SettlementBounds;
import com.ultimakingdoms.api.SettlementCandidate;
import com.ultimakingdoms.api.SettlementDetector;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class VillageStructureDetector implements SettlementDetector {
    private static final ResourceLocation ID = new ResourceLocation(UltimaKingdomsApi.MOD_ID, "village_structure");

    @Override
    public Stream<SettlementCandidate> detect(ServerLevel level, BlockPos center, int radiusChunks) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        Stream.Builder<SettlementCandidate> candidates = Stream.builder();
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (int chunkX = centerChunkX - radiusChunks; chunkX <= centerChunkX + radiusChunks; chunkX++) {
            for (int chunkZ = centerChunkZ - radiusChunks; chunkZ <= centerChunkZ + radiusChunks; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                for (Map.Entry<Structure, net.minecraft.world.level.levelgen.structure.StructureStart> entry
                        : level.getChunk(chunkX, chunkZ).getAllStarts().entrySet()) {
                    if (!entry.getValue().isValid() || !structures.wrapAsHolder(entry.getKey()).is(StructureTags.VILLAGE)) {
                        continue;
                    }
                    ResourceLocation structureId = structures.getKey(entry.getKey());
                    if (structureId == null) continue;
                    BoundingBox box = entry.getValue().getBoundingBox();
                    BlockPos anchor = box.getCenter().immutable();
                    int radius = Math.max(box.getXSpan(), box.getZSpan()) / 2;
                    ChunkPos startChunk = entry.getValue().getChunkPos();
                    String sourceKey = level.dimension().location() + ":" + structureId + ":"
                            + startChunk.x + ":" + startChunk.z;
                    candidates.add(new SettlementCandidate(level.dimension(), anchor, Math.max(16, radius),
                            new SettlementBounds(box.minX(), box.minZ(), box.maxX(), box.maxZ()), ID, sourceKey,
                            DetectionSource.STRUCTURE, Optional.of(structureId), Optional.empty(),
                            Map.of("minecraft:structure", sourceKey), Optional.empty()));
                }
            }
        }
        return candidates.build();
    }
}
