package com.ultimakingdoms.api.townstead;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownsteadBindingPersistenceTest {
    private static final ResourceLocation DIMENSION = new ResourceLocation("minecraft", "overworld");
    private static final UUID SETTLEMENT = UUID.fromString("bcbab0d4-11b3-4fd7-8038-aad95b5d89fd");

    @Test
    void derivesStableBindingAndBuildingFamily() {
        assertEquals("townstead:kitchen", TownsteadBuildingView.familyOf("townstead:kitchen_l3"));
        assertEquals(3, TownsteadBuildingView.levelOf("townstead:kitchen_l3"));
        assertEquals("townstead:kitchen_large", TownsteadBuildingView.familyOf("townstead:kitchen_large"));

        UUID first = TownsteadBuildingView.bindingId(DIMENSION, SETTLEMENT, 12, 7);
        UUID repeated = TownsteadBuildingView.bindingId(DIMENSION, SETTLEMENT, 12, 7);
        assertEquals(first, repeated);
        assertNotEquals(first, TownsteadBuildingView.bindingId(DIMENSION, SETTLEMENT, 12, 8));
    }

    @Test
    void codecAndNbtRoundTripIdentifiersOnly() {
        BoundCivicBuilding binding = binding(7, "townstead:kitchen");
        JsonElement encoded = BoundCivicBuilding.CODEC.encodeStart(JsonOps.INSTANCE, binding)
                .getOrThrow(false, message -> { throw new AssertionError(message); });
        BoundCivicBuilding decoded = BoundCivicBuilding.CODEC.parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(false, message -> { throw new AssertionError(message); });
        assertEquals(binding, decoded);

        CompoundTag tag = TownsteadBindingPersistence.save(binding);
        assertEquals(binding, TownsteadBindingPersistence.load(tag).orElseThrow());
        assertEquals(7, tag.size());
    }

    @Test
    void malformedNbtFailsClosed() {
        CompoundTag tag = TownsteadBindingPersistence.save(binding(7, "townstead:kitchen"));
        tag.putString("Dimension", "not a resource id");
        assertTrue(TownsteadBindingPersistence.load(tag).isEmpty());
    }

    private static BoundCivicBuilding binding(int buildingId, String family) {
        return new BoundCivicBuilding(
                TownsteadBuildingView.bindingId(DIMENSION, SETTLEMENT, 12, buildingId),
                SETTLEMENT, DIMENSION, 12, buildingId, family, family + "_l2");
    }
}
