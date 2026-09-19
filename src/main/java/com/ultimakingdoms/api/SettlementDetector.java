package com.ultimakingdoms.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.stream.Stream;

@FunctionalInterface
public interface SettlementDetector {
    Stream<SettlementCandidate> detect(ServerLevel level, BlockPos center, int radiusChunks);
}
