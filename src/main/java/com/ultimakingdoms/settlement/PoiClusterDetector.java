package com.ultimakingdoms.settlement;

import com.ultimakingdoms.api.SettlementCandidate;
import com.ultimakingdoms.api.SettlementDetector;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.stream.Stream;

public final class PoiClusterDetector implements SettlementDetector {
    private static final ResourceLocation ID = new ResourceLocation(UltimaKingdomsApi.MOD_ID, "poi_cluster");
    private final int searchRadius;
    private final int minimumPoiCount;
    private final int settlementRadius;

    public PoiClusterDetector(int searchRadius, int minimumPoiCount, int settlementRadius) {
        this.searchRadius = searchRadius;
        this.minimumPoiCount = minimumPoiCount;
        this.settlementRadius = settlementRadius;
    }

    @Override
    public Stream<SettlementCandidate> detect(ServerLevel level, BlockPos center, int radiusChunks) {
        int scanRadius = Math.max(searchRadius, radiusChunks * 16);
        List<BlockPos> positions = level.getPoiManager()
                .getInRange(holder -> holder.is(PoiTypeTags.VILLAGE), center, scanRadius, PoiManager.Occupancy.ANY)
                .map(record -> record.getPos().immutable())
                .toList();
        if (positions.size() < minimumPoiCount) return Stream.empty();

        long sumX = 0;
        long sumY = 0;
        long sumZ = 0;
        for (BlockPos position : positions) {
            sumX += position.getX();
            sumY += position.getY();
            sumZ += position.getZ();
        }
        BlockPos anchor = new BlockPos((int) (sumX / positions.size()), (int) (sumY / positions.size()),
                (int) (sumZ / positions.size()));
        ChunkPos chunk = new ChunkPos(anchor);
        String sourceKey = level.dimension().location() + ":poi:" + chunk.x + ":" + chunk.z;
        return Stream.of(new SettlementCandidate(level.dimension(), anchor, settlementRadius,
                com.ultimakingdoms.api.SettlementBounds.around(anchor, settlementRadius), ID, sourceKey,
                com.ultimakingdoms.api.DetectionSource.POI, java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Map.of(), java.util.Optional.empty()));
    }
}
