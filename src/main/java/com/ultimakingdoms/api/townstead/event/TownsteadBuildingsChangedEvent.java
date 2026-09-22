package com.ultimakingdoms.api.townstead.event;

import com.ultimakingdoms.api.townstead.TownsteadBuildingFingerprint;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.Event;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class TownsteadBuildingsChangedEvent extends Event {
    private final UUID settlementId;
    private final ResourceLocation dimension;
    private final List<TownsteadBuildingFingerprint> previous;
    private final List<TownsteadBuildingFingerprint> current;

    public TownsteadBuildingsChangedEvent(UUID settlementId, ResourceLocation dimension,
                                          List<TownsteadBuildingFingerprint> previous,
                                          List<TownsteadBuildingFingerprint> current) {
        this.settlementId = Objects.requireNonNull(settlementId, "settlementId");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.previous = List.copyOf(previous);
        this.current = List.copyOf(current);
    }

    public UUID settlementId() { return settlementId; }
    public ResourceLocation dimension() { return dimension; }
    public List<TownsteadBuildingFingerprint> previous() { return previous; }
    public List<TownsteadBuildingFingerprint> current() { return current; }
}
