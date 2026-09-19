package com.ultimakingdoms.api;

import net.minecraft.core.BlockPos;

public record SettlementBounds(int minX, int minZ, int maxX, int maxZ) {
    public SettlementBounds {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Settlement bounds minimum must not exceed maximum");
        }
    }

    public static SettlementBounds around(BlockPos anchor, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("Settlement radius must be non-negative");
        }
        return new SettlementBounds(
                anchor.getX() - radius,
                anchor.getZ() - radius,
                anchor.getX() + radius,
                anchor.getZ() + radius
        );
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getZ());
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean intersects(SettlementBounds other) {
        return minX <= other.maxX && maxX >= other.minX
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
