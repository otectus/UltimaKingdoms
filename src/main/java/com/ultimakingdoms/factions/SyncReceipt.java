package com.ultimakingdoms.factions;

import com.ultimakingdoms.api.factions.FactionStandingRequest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

record SyncReceipt(UUID correlationId, UUID playerId, ResourceLocation source,
                   ResourceLocation targetKingdom, long sourceRevision, long targetRevision,
                   long gameTime, Optional<UUID> settlementId) {
    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Correlation", correlationId);
        tag.putUUID("Player", playerId);
        tag.putString("Source", source.toString());
        tag.putString("Target", targetKingdom.toString());
        tag.putLong("SourceRevision", sourceRevision);
        tag.putLong("TargetRevision", targetRevision);
        tag.putLong("GameTime", gameTime);
        settlementId.ifPresent(id -> tag.putUUID("Settlement", id));
        return tag;
    }

    static SyncReceipt from(FactionStandingRequest request, long targetRevision, long gameTime) {
        return new SyncReceipt(request.correlationId(), request.playerId(), request.source(),
                request.kingdomId(), request.sourceRevision(), targetRevision, gameTime, request.settlementId());
    }

    static SyncReceipt load(CompoundTag tag) {
        ResourceLocation source = ResourceLocation.tryParse(tag.getString("Source"));
        ResourceLocation target = ResourceLocation.tryParse(tag.getString("Target"));
        if (source == null || target == null) throw new IllegalArgumentException("Invalid sync receipt scope");
        return new SyncReceipt(tag.getUUID("Correlation"), tag.getUUID("Player"), source, target,
                Math.max(0L, tag.getLong("SourceRevision")), Math.max(0L, tag.getLong("TargetRevision")),
                Math.max(0L, tag.getLong("GameTime")), tag.hasUUID("Settlement")
                ? Optional.of(tag.getUUID("Settlement")) : Optional.empty());
    }
}
