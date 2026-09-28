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

    /** Village points of interest closer than this belong to the same settlement. */
    static final int LINK_DISTANCE = 40;
    private static final int EDGE_MARGIN = 16;
    @Override
    public Stream<SettlementCandidate> detect(ServerLevel level, BlockPos center, int radiusChunks) {
        int scanRadius = Math.max(searchRadius, radiusChunks * 16);
        List<BlockPos> positions = level.getPoiManager()
                .getInRange(holder -> holder.is(PoiTypeTags.VILLAGE), center, scanRadius, PoiManager.Occupancy.ANY)
                .map(record -> record.getPos().immutable())
                .toList();
        if (positions.size() < minimumPoiCount) return Stream.empty();
        var candidates = new java.util.ArrayList<SettlementCandidate>();
        for (List<BlockPos> cluster : clusters(positions, LINK_DISTANCE)) {
            if (cluster.size() < minimumPoiCount) continue;
            long sumX = 0, sumY = 0, sumZ = 0; int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos position : cluster) {
                sumX += position.getX(); sumY += position.getY(); sumZ += position.getZ();
                minX = Math.min(minX, position.getX()); maxX = Math.max(maxX, position.getX());
                minZ = Math.min(minZ, position.getZ()); maxZ = Math.max(maxZ, position.getZ());
            }
            BlockPos anchor = new BlockPos((int) (sumX / cluster.size()), (int) (sumY / cluster.size()), (int) (sumZ / cluster.size()));
            // The radius follows the cluster's real extent so that neighbouring scans of one large village overlap
            // substantially and merge into a single record instead of registering several villages.
            int extent = Math.max(Math.max(anchor.getX() - minX, maxX - anchor.getX()), Math.max(anchor.getZ() - minZ, maxZ - anchor.getZ())) + EDGE_MARGIN;
            int radius = Math.min(Math.max(settlementRadius, extent), settlementRadius * 4);
            ChunkPos chunk = new ChunkPos(anchor);
            String sourceKey = level.dimension().location() + ":poi:" + chunk.x + ":" + chunk.z;
            candidates.add(new SettlementCandidate(level.dimension(), anchor, radius,
                    com.ultimakingdoms.api.SettlementBounds.around(anchor, radius), ID, sourceKey,
                    com.ultimakingdoms.api.DetectionSource.POI, java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Map.of(), java.util.Optional.empty()));
        }
        return candidates.stream();
    }
    /** Points closer than {@code linkDistance} (horizontally) belong to one settlement; groups are found by union-find. */
    static List<List<BlockPos>> clusters(List<BlockPos> positions, int linkDistance) {
        int n = positions.size(); int[] parent = new int[n]; for (int i = 0; i < n; i++) parent[i] = i;
        long limit = (long) linkDistance * linkDistance;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            long dx = positions.get(i).getX() - positions.get(j).getX(), dz = positions.get(i).getZ() - positions.get(j).getZ();
            if (dx * dx + dz * dz <= limit) { int a = find(parent, i), b = find(parent, j); if (a != b) parent[a] = b; }
        }
        var groups = new java.util.LinkedHashMap<Integer, List<BlockPos>>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), ignored -> new java.util.ArrayList<>()).add(positions.get(i));
        return List.copyOf(groups.values());
    }
    private static int find(int[] parent, int i) { while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
}
