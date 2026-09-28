package com.ultimakingdoms.settlement;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PoiClusterDetectorTest {
    @Test void separatedGroupsFormSeparateClusters() {
        var positions = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 4), new BlockPos(20, 64, 8),
                new BlockPos(300, 64, 0), new BlockPos(310, 64, 6), new BlockPos(322, 64, 2));
        var clusters = PoiClusterDetector.clusters(positions, 32);
        assertEquals(2, clusters.size());
        assertTrue(clusters.stream().allMatch(c -> c.size() == 3));
    }
    @Test void aChainOfNearbyPointsIsOneCluster() {
        var positions = List.of(new BlockPos(0, 64, 0), new BlockPos(30, 64, 0), new BlockPos(60, 64, 0), new BlockPos(90, 64, 0), new BlockPos(120, 64, 0));
        assertEquals(1, PoiClusterDetector.clusters(positions, 32).size());
        assertEquals(5, PoiClusterDetector.clusters(positions, 20).size());
    }
}
