package com.ultimakingdoms.api.townstead;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record TownsteadBuildingView(
        ResourceLocation dimension, UUID settlementId, UUID bindingId,
        int villageId, int buildingId, String type, String family, int level, int size,
        int centerX, int centerY, int centerZ,
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ
) {
    public TownsteadBuildingView {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(family, "family");
        if (villageId < 0 || buildingId < 0 || level < 1 || size < 0
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid Townstead building snapshot");
        }
    }

    public BlockPos center() {
        return new BlockPos(centerX, centerY, centerZ);
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    public static String familyOf(String type) {
        Objects.requireNonNull(type, "type");
        int marker = type.lastIndexOf("_l");
        if (marker <= 0) return type;
        String suffix = type.substring(marker + 2);
        return suffix.isEmpty() || suffix.length() > 9 || !suffix.chars().allMatch(Character::isDigit)
                ? type : type.substring(0, marker);
    }

    public static int levelOf(String type) {
        String family = familyOf(type);
        if (family.equals(type)) return 1;
        try {
            return Math.max(1, Integer.parseInt(type.substring(family.length() + 2)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    public static UUID bindingId(ResourceLocation dimension, UUID settlementId, int villageId, int buildingId) {
        String key = dimension + "|" + settlementId + "|" + villageId + "|" + buildingId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
