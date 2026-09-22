package com.ultimakingdoms.factions;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class FactionSavedData extends SavedData {
    static final String DATA_NAME = "ultima_kingdoms_factions";
    static final int SCHEMA = 1;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<Key, FactionStandingRecord> standings = new LinkedHashMap<>();
    private final Map<UUID, SyncReceipt> receipts = new LinkedHashMap<>();
    private final Map<String, Long> sourceCheckpoints = new LinkedHashMap<>();
    private final Map<String, SourceCursor> sourceCursors = new LinkedHashMap<>();
    private final Map<UUID, PendingReputationChange> pending = new LinkedHashMap<>();
    private final Map<UUID, IgnoredSourceReceipt> ignoredReceipts = new LinkedHashMap<>();
    private final Map<UUID, ShadowProjection> shadow = new LinkedHashMap<>();
    private final Set<String> migrationMarkers = new java.util.LinkedHashSet<>();
    private final List<Runnable> durableListeners = new ArrayList<>();
    private Map<String, SourceCursor> durableCursors = Map.of();
    private long revision;
    private long ignoredConsumed;
    private boolean readOnly;
    private CompoundTag retainedRaw;

    static FactionSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                FactionSavedData::load, FactionSavedData::new, DATA_NAME);
    }

    boolean writable() {
        return !readOnly;
    }

    long revision() {
        return revision;
    }

    Optional<FactionStandingRecord> standing(UUID player, ResourceLocation kingdom) {
        return Optional.ofNullable(standings.get(new Key(player, kingdom)));
    }

    Collection<FactionStandingRecord> standings() {
        return List.copyOf(standings.values());
    }

    FactionStandingRecord getOrCreate(UUID player, ResourceLocation kingdom) {
        if (!writable()) throw new IllegalStateException("Faction data is read-only");
        return standings.computeIfAbsent(new Key(player, kingdom), ignored -> new FactionStandingRecord(player, kingdom));
    }

    Optional<SyncReceipt> receipt(UUID correlation) {
        return Optional.ofNullable(receipts.get(correlation));
    }

    long checkpoint(ResourceLocation source) {
        return sourceCheckpoints.getOrDefault(source.toString(), 0L);
    }

    long nextRevision() {
        return ++revision;
    }

    void record(SyncReceipt receipt) {
        receipts.put(receipt.correlationId(), receipt);
        if (receipt.sourceRevision() > 0) {
            sourceCheckpoints.merge(receipt.source().toString(), receipt.sourceRevision(), Math::max);
        }
        setDirty();
    }

    void pruneReceipts(long now, long retention) {
        if (!writable()) return;
        if (receipts.entrySet().removeIf(entry -> {
            SyncReceipt receipt = entry.getValue();
            return receipt.sourceRevision() > 0
                    && receipt.sourceRevision() <= checkpoint(receipt.source())
                    && now - receipt.gameTime() > retention;
        })) setDirty();
    }

    SourceCursor cursor(String consumer) {
        return sourceCursors.get(consumer);
    }

    SourceCursor durableCursor(String consumer) {
        return durableCursors.get(consumer);
    }

    void advanceCursor(String consumer, UUID epoch, long through) {
        validateCursorAdvance(consumer, epoch, through);
        SourceCursor replacement = new SourceCursor(epoch, through);
        if (replacement.equals(sourceCursors.put(consumer, replacement))) return;
        setDirty();
    }

    private void validateCursorAdvance(String consumer, UUID epoch, long through) {
        SourceCursor current = sourceCursors.get(consumer);
        if (current != null && current.epoch.equals(epoch) && through < current.through) {
            throw new IllegalArgumentException("Source cursor cannot move backwards");
        }
    }

    boolean takeCustody(PendingReputationChange change, int capacity) {
        if (!writable()) return false;
        if (pending.containsKey(change.eventId())) return true;
        if (pending.size() >= capacity) return false;
        pending.put(change.eventId(), change);setDirty();return true;
    }

    Collection<PendingReputationChange> pending() {
        return List.copyOf(pending.values());
    }

    Optional<PendingReputationChange> pending(UUID eventId) {
        return Optional.ofNullable(pending.get(eventId));
    }

    void resolvePending(UUID eventId) {
        if (pending.remove(eventId) != null) setDirty();
    }

    boolean consumeIgnored(IgnoredSourceReceipt receipt, int capacity, String consumer,
                           boolean advanceCursor) {
        if (!writable() || capacity < 1) return false;
        if (advanceCursor) validateCursorAdvance(consumer, receipt.epoch(), receipt.sequence());
        if (!ignoredReceipts.containsKey(receipt.eventId())) {
            while (ignoredReceipts.size() >= capacity) {
                var iterator = ignoredReceipts.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            ignoredReceipts.put(receipt.eventId(), receipt);
            ignoredConsumed++;
            setDirty();
        }
        if (advanceCursor) advanceCursor(consumer, receipt.epoch(), receipt.sequence());
        return true;
    }

    int ignoredReceiptCount() {
        return ignoredReceipts.size();
    }

    long ignoredConsumed() {
        return ignoredConsumed;
    }

    void recordShadow(ShadowProjection projection) {
        if (shadow.putIfAbsent(projection.eventId, projection) == null) setDirty();
    }

    Collection<ShadowProjection> shadow() {
        return List.copyOf(shadow.values());
    }

    boolean hasMigration(String marker) {
        return migrationMarkers.contains(marker);
    }

    void markMigration(String marker) {
        if (migrationMarkers.add(marker)) setDirty();
    }

    void onDurableSave(Runnable listener) {
        durableListeners.add(listener);
    }

    @Override
    public void setDirty(boolean dirty) {
        if (!readOnly) super.setDirty(dirty);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (readOnly && retainedRaw != null) {
            retainedRaw.getAllKeys().forEach(key -> tag.put(key, retainedRaw.get(key).copy()));
            return tag;
        }
        tag.putInt("Schema", SCHEMA);tag.putLong("Revision", revision);
        ListTag standingTags = new ListTag();standings.values().forEach(value -> standingTags.add(value.save()));
        tag.put("Standings", standingTags);
        ListTag receiptTags = new ListTag();receipts.values().forEach(value -> receiptTags.add(value.save()));
        tag.put("SyncReceipts", receiptTags);
        CompoundTag checkpointTag = new CompoundTag();sourceCheckpoints.forEach(checkpointTag::putLong);
        tag.put("SourceCheckpoints", checkpointTag);
        ListTag cursorTags = new ListTag();sourceCursors.forEach((consumer,cursor)->cursorTags.add(cursor.save(consumer)));
        tag.put("SourceCursors",cursorTags);
        ListTag pendingTags=new ListTag();pending.values().forEach(value->pendingTags.add(value.save()));tag.put("Pending",pendingTags);
        ListTag ignoredTags=new ListTag();ignoredReceipts.values().forEach(value->ignoredTags.add(value.save()));tag.put("IgnoredReceipts",ignoredTags);
        tag.putLong("IgnoredConsumed", ignoredConsumed);
        ListTag shadowTags=new ListTag();shadow.values().forEach(value->shadowTags.add(value.save()));tag.put("Shadow",shadowTags);
        ListTag migrations=new ListTag();migrationMarkers.forEach(value->migrations.add(net.minecraft.nbt.StringTag.valueOf(value)));
        tag.put("Migrations",migrations);
        return tag;
    }

    @Override
    public void save(File file) {
        if (!isDirty()) return;
        try {
            DurableSavedDataIO.write(file, save(new CompoundTag()));
            durableCursors = Map.copyOf(sourceCursors);
            setDirty(false);
            List.copyOf(durableListeners).forEach(listener -> {
                try { listener.run(); } catch (RuntimeException exception) {
                    LOGGER.error("Faction durable-save listener failed", exception);
                }
            });
        } catch (IOException exception) {
            LOGGER.error("Could not durably save faction data {}", file, exception);
            setDirty(true);
        }
    }

    static FactionSavedData load(CompoundTag tag) {
        FactionSavedData data = new FactionSavedData();
        int schema = tag.contains("Schema") ? tag.getInt("Schema") : 1;
        if (schema > SCHEMA) {
            data.readOnly=true;data.retainedRaw=tag.copy();
            LOGGER.error("Faction data schema {} is newer than supported schema {}; preserving it read-only",schema,SCHEMA);
            return data;
        }
        data.revision=Math.max(0L,tag.getLong("Revision"));
        for(Tag value:tag.getList("Standings",Tag.TAG_COMPOUND)) try {
            FactionStandingRecord record=FactionStandingRecord.load((CompoundTag)value);
            data.standings.put(new Key(record.playerId,record.kingdomId),record);
        } catch(RuntimeException exception){LOGGER.warn("Skipping malformed faction standing",exception);}
        for(Tag value:tag.getList("SyncReceipts",Tag.TAG_COMPOUND)) try {
            SyncReceipt receipt=SyncReceipt.load((CompoundTag)value);data.receipts.put(receipt.correlationId(),receipt);
        } catch(RuntimeException exception){LOGGER.warn("Skipping malformed faction sync receipt",exception);}
        CompoundTag checkpoints=tag.getCompound("SourceCheckpoints");checkpoints.getAllKeys().forEach(key->data.sourceCheckpoints.put(key,Math.max(0L,checkpoints.getLong(key))));
        for(Tag value:tag.getList("SourceCursors",Tag.TAG_COMPOUND)) try {
            CompoundTag cursor=(CompoundTag)value;data.sourceCursors.put(cursor.getString("Consumer"),SourceCursor.load(cursor));
        }catch(RuntimeException exception){LOGGER.warn("Skipping malformed faction source cursor",exception);}
        for(Tag value:tag.getList("Pending",Tag.TAG_COMPOUND)) try {PendingReputationChange pending=PendingReputationChange.load((CompoundTag)value);data.pending.put(pending.eventId(),pending);}catch(RuntimeException exception){LOGGER.warn("Skipping malformed pending reputation change",exception);}
        for(Tag value:tag.getList("IgnoredReceipts",Tag.TAG_COMPOUND)) try {IgnoredSourceReceipt receipt=IgnoredSourceReceipt.load((CompoundTag)value);data.ignoredReceipts.put(receipt.eventId(),receipt);}catch(RuntimeException exception){LOGGER.warn("Skipping malformed ignored reputation receipt",exception);}
        data.ignoredConsumed=Math.max(data.ignoredReceipts.size(),Math.max(0L,tag.getLong("IgnoredConsumed")));
        for(Tag value:tag.getList("Shadow",Tag.TAG_COMPOUND)) try {ShadowProjection shadow=ShadowProjection.load((CompoundTag)value);data.shadow.put(shadow.eventId,shadow);}catch(RuntimeException exception){LOGGER.warn("Skipping malformed shadow projection",exception);}
        for(Tag value:tag.getList("Migrations",Tag.TAG_STRING))data.migrationMarkers.add(value.getAsString());
        data.durableCursors=Map.copyOf(data.sourceCursors);
        return data;
    }

    record Key(UUID player, ResourceLocation kingdom) {}

    record SourceCursor(UUID epoch,long through) {
        CompoundTag save(String consumer){CompoundTag tag=new CompoundTag();tag.putString("Consumer",consumer);tag.putUUID("Epoch",epoch);tag.putLong("Through",through);return tag;}
        static SourceCursor load(CompoundTag tag){return new SourceCursor(tag.getUUID("Epoch"),Math.max(0L,tag.getLong("Through")));}
    }

    record IgnoredSourceReceipt(UUID epoch, long sequence, UUID eventId, String reason, long gameTime) {
        IgnoredSourceReceipt {
            if (sequence < 0L || gameTime < 0L) throw new IllegalArgumentException("negative ignored receipt field");
            java.util.Objects.requireNonNull(epoch, "epoch");
            java.util.Objects.requireNonNull(eventId, "eventId");
            reason = reason == null || reason.isBlank() ? "unspecified" : reason;
        }

        CompoundTag save() {
            CompoundTag tag=new CompoundTag();tag.putUUID("Epoch",epoch);tag.putLong("Sequence",sequence);
            tag.putUUID("Event",eventId);tag.putString("Reason",reason);tag.putLong("GameTime",gameTime);return tag;
        }

        static IgnoredSourceReceipt load(CompoundTag tag) {
            return new IgnoredSourceReceipt(tag.getUUID("Epoch"),Math.max(0L,tag.getLong("Sequence")),
                    tag.getUUID("Event"),tag.getString("Reason"),Math.max(0L,tag.getLong("GameTime")));
        }
    }

    record ShadowProjection(UUID eventId,UUID player,ResourceLocation kingdom,int projectedDelta,long sourceSequence,String outcome) {
        CompoundTag save(){CompoundTag tag=new CompoundTag();tag.putUUID("Event",eventId);tag.putUUID("Player",player);tag.putString("Kingdom",kingdom.toString());tag.putInt("Delta",projectedDelta);tag.putLong("Sequence",sourceSequence);tag.putString("Outcome",outcome);return tag;}
        static ShadowProjection load(CompoundTag tag){ResourceLocation kingdom=ResourceLocation.tryParse(tag.getString("Kingdom"));if(kingdom==null)throw new IllegalArgumentException("invalid shadow kingdom");return new ShadowProjection(tag.getUUID("Event"),tag.getUUID("Player"),kingdom,tag.getInt("Delta"),tag.getLong("Sequence"),tag.getString("Outcome"));}
    }
}
