package com.ultimakingdoms.api;

import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

@FunctionalInterface
public interface KingdomResolver {
    Optional<KingdomResolution> resolve(ServerLevel level, SettlementCandidate candidate);
}
