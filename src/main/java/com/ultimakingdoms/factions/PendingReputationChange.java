package com.ultimakingdoms.factions;

import net.minecraft.nbt.CompoundTag;

import java.util.Map;
import java.util.UUID;

record PendingReputationChange(UUID epoch, long sequence, UUID eventId, String status,
                               Map<String, String> payload) {
    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Epoch", epoch);tag.putLong("Sequence", sequence);tag.putUUID("Event", eventId);
        tag.putString("Status", status);
        CompoundTag values = new CompoundTag();payload.forEach(values::putString);tag.put("Payload", values);
        return tag;
    }

    static PendingReputationChange load(CompoundTag tag) {
        java.util.LinkedHashMap<String,String> payload = new java.util.LinkedHashMap<>();
        CompoundTag values=tag.getCompound("Payload");values.getAllKeys().forEach(k->payload.put(k,values.getString(k)));
        return new PendingReputationChange(tag.getUUID("Epoch"),Math.max(0L,tag.getLong("Sequence")),
                tag.getUUID("Event"),tag.getString("Status"),Map.copyOf(payload));
    }
}
