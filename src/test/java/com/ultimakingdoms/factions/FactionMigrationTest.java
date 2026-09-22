package com.ultimakingdoms.factions;

import com.ultimakingdoms.factions.config.FactionConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactionMigrationTest {
    @Test
    void defaultMeanDoesNotMultiplyStandingBySettlementCount() {
        assertEquals(100, FactionServiceImpl.aggregate(List.of(100, 100, 100, 100, 100),
                FactionConfig.LegacyAggregation.MEAN));
        assertEquals(17, FactionServiceImpl.aggregate(List.of(0, 0, 50),
                FactionConfig.LegacyAggregation.MEAN));
    }

    @Test
    void configuredAggregationsAreDeterministicAndSumIsClamped() {
        List<Integer> values = List.of(-100, 20, 90, 200);
        assertEquals(53, FactionServiceImpl.aggregate(values, FactionConfig.LegacyAggregation.MEAN));
        assertEquals(55, FactionServiceImpl.aggregate(values, FactionConfig.LegacyAggregation.MEDIAN));
        assertEquals(-100, FactionServiceImpl.aggregate(values, FactionConfig.LegacyAggregation.MIN));
        assertEquals(200, FactionServiceImpl.aggregate(values, FactionConfig.LegacyAggregation.MAX));
        assertEquals(1_000, FactionServiceImpl.aggregate(List.of(800, 700),
                FactionConfig.LegacyAggregation.SUM));
    }
}
