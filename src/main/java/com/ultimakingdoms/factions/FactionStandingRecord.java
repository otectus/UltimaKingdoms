package com.ultimakingdoms.factions;

import com.ultimakingdoms.api.factions.FactionStandingSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

final class FactionStandingRecord {
    final UUID playerId;
    final ResourceLocation kingdomId;
    int score;
    String tierId;
    String highWaterTierId;
    long revision;

    FactionStandingRecord(UUID playerId, ResourceLocation kingdomId) {
        this.playerId = playerId;
        this.kingdomId = kingdomId;
        this.tierId = FactionTierScale.tier(0);
        this.highWaterTierId = tierId;
    }

    FactionStandingSnapshot snapshot() {
        return new FactionStandingSnapshot(playerId, kingdomId, score, FactionTierScale.LADDER,
                tierId, highWaterTierId, revision);
    }

    int apply(int requested, long newRevision) {
        int old = score;
        long candidate = (long) score + requested;
        score = (int) Math.max(-1_000L, Math.min(1_000L, candidate));
        tierId = FactionTierScale.tier(score);
        if (FactionTierScale.rank(tierId) > FactionTierScale.rank(highWaterTierId)) highWaterTierId = tierId;
        revision = newRevision;
        return score - old;
    }

    void importBaseline(int baseline, long newRevision) {
        score = Math.max(-1_000, Math.min(1_000, baseline));
        tierId = FactionTierScale.tier(score);
        highWaterTierId = tierId;
        revision = newRevision;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Player", playerId);
        tag.putString("Kingdom", kingdomId.toString());
        tag.putInt("Score", score);
        tag.putString("Tier", tierId);
        tag.putString("HighWaterTier", highWaterTierId);
        tag.putLong("Revision", revision);
        return tag;
    }

    static FactionStandingRecord load(CompoundTag tag) {
        UUID player = tag.getUUID("Player");
        ResourceLocation kingdom = ResourceLocation.tryParse(tag.getString("Kingdom"));
        if (kingdom == null) throw new IllegalArgumentException("Invalid faction kingdom id");
        FactionStandingRecord record = new FactionStandingRecord(player, kingdom);
        record.score = Math.max(-1_000, Math.min(1_000, tag.getInt("Score")));
        record.tierId = FactionTierScale.tier(record.score);
        String highWater = tag.getString("HighWaterTier");
        record.highWaterTierId = FactionTierScale.rank(highWater) >= FactionTierScale.rank(record.tierId)
                ? highWater : record.tierId;
        record.revision = Math.max(0L, tag.getLong("Revision"));
        return record;
    }
}
