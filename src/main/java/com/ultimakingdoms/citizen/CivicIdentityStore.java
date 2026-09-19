package com.ultimakingdoms.citizen;

import com.ultimakingdoms.api.CivicIdentitySource;
import com.ultimakingdoms.core.CivicIdentitySnapshot;
import com.ultimakingdoms.core.SettlementSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class CivicIdentityStore {
    private static final String KEY = "ultima_kingdoms:civic_identity";

    public Optional<CivicIdentitySnapshot> read(Entity entity, Function<UUID, Optional<SettlementSnapshot>> settlements) {
        CompoundTag root = entity.getPersistentData();
        if (!root.contains(KEY, Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag tag = root.getCompound(KEY);
        Optional<UUID> origin = tag.hasUUID("OriginSettlement")
                ? Optional.of(tag.getUUID("OriginSettlement")) : Optional.empty();
        Optional<ResourceLocation> originKingdom = optionalId(tag.getString("OriginKingdom"));
        Optional<UUID> residence = tag.hasUUID("ResidenceSettlement")
                ? Optional.of(tag.getUUID("ResidenceSettlement")) : Optional.empty();
        Optional<ResourceLocation> residenceKingdom = residence
                .flatMap(settlements)
                .map(SettlementSnapshot::kingdomId);
        CivicIdentitySource source;
        try {
            source = CivicIdentitySource.valueOf(tag.getString("Source"));
        } catch (IllegalArgumentException ignored) {
            source = CivicIdentitySource.MIGRATION;
        }
        return Optional.of(new CivicIdentitySnapshot(origin, originKingdom, residence, residenceKingdom,
                source, tag.getLong("LastResidenceChange")));
    }

    public CivicIdentitySnapshot write(Entity entity, CivicIdentitySnapshot identity) {
        CompoundTag tag = new CompoundTag();
        identity.originSettlement().ifPresent(value -> tag.putUUID("OriginSettlement", value));
        identity.originKingdom().ifPresent(value -> tag.putString("OriginKingdom", value.toString()));
        identity.residenceSettlement().ifPresent(value -> tag.putUUID("ResidenceSettlement", value));
        tag.putString("Source", identity.source().name());
        tag.putLong("LastResidenceChange", identity.lastResidenceChange());
        entity.getPersistentData().put(KEY, tag);
        return identity;
    }

    public void copy(Entity source, Entity target) {
        CompoundTag sourceData = source.getPersistentData();
        if (sourceData.contains(KEY, Tag.TAG_COMPOUND)) {
            target.getPersistentData().put(KEY, sourceData.getCompound(KEY).copy());
        }
    }

    private static Optional<ResourceLocation> optionalId(String value) {
        if (value.isBlank()) return Optional.empty();
        return Optional.ofNullable(ResourceLocation.tryParse(value));
    }
}
