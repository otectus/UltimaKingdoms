package com.ultimakingdoms.api.townstead;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/** NBT boundary for identifier-only civic building bindings. */
public final class TownsteadBindingPersistence {
    private TownsteadBindingPersistence() {
    }

    public static CompoundTag save(BoundCivicBuilding binding) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Binding", binding.bindingId());
        tag.putUUID("Settlement", binding.settlementId());
        tag.putString("Dimension", binding.dimension().toString());
        tag.putInt("Village", binding.villageId());
        tag.putInt("Building", binding.buildingId());
        tag.putString("Family", binding.family());
        tag.putString("Type", binding.typeAtBinding());
        return tag;
    }

    public static Optional<BoundCivicBuilding> load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("Binding") || !tag.hasUUID("Settlement")) return Optional.empty();
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null || tag.getInt("Village") < 0 || tag.getInt("Building") < 0
                || tag.getString("Family").isBlank() || tag.getString("Type").isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BoundCivicBuilding(tag.getUUID("Binding"), tag.getUUID("Settlement"), dimension,
                    tag.getInt("Village"), tag.getInt("Building"), tag.getString("Family"), tag.getString("Type")));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
