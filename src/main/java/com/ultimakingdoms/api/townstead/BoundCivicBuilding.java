package com.ultimakingdoms.api.townstead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Stable foreign identifiers for a Townstead building binding. */
public record BoundCivicBuilding(
        UUID bindingId, UUID settlementId, ResourceLocation dimension,
        int villageId, int buildingId, String family, String typeAtBinding
) {
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);
    public static final Codec<BoundCivicBuilding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUID_CODEC.fieldOf("binding_id").forGetter(BoundCivicBuilding::bindingId),
            UUID_CODEC.fieldOf("settlement_id").forGetter(BoundCivicBuilding::settlementId),
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(BoundCivicBuilding::dimension),
            Codec.INT.fieldOf("village_id").forGetter(BoundCivicBuilding::villageId),
            Codec.INT.fieldOf("building_id").forGetter(BoundCivicBuilding::buildingId),
            Codec.STRING.fieldOf("family").forGetter(BoundCivicBuilding::family),
            Codec.STRING.fieldOf("type_at_binding").forGetter(BoundCivicBuilding::typeAtBinding)
    ).apply(instance, BoundCivicBuilding::new));

    public BoundCivicBuilding {
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(typeAtBinding, "typeAtBinding");
        if (villageId < 0 || buildingId < 0 || family.isBlank() || typeAtBinding.isBlank()) {
            throw new IllegalArgumentException("Invalid civic building binding");
        }
    }

    public static BoundCivicBuilding from(TownsteadBuildingView building) {
        Objects.requireNonNull(building, "building");
        return new BoundCivicBuilding(building.bindingId(), building.settlementId(), building.dimension(),
                building.villageId(), building.buildingId(), building.family(), building.type());
    }
}
