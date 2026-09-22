package dev.otectus.mcaquests.state;

import dev.otectus.mcaquests.api.QuestCompletionReceipt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persisted, bounded per-player completion outbox. Package-private; add-ons use {@code McaQuestsApi}. */
final class CompletionReceiptOutbox {
    static final int SCHEMA = 3;
    static final int MAX_RECEIPTS = 256;
    static final int MAX_ACK_TOMBSTONES = 512;
    static final int MAX_CONSUMERS = 32;
    /** Seven real-time days at the normal 20 TPS. Only fully acknowledged entries can age out. */
    static final long ACKNOWLEDGED_RETENTION_TICKS = 12_096_000L;
    /** Consumers renew this through normal polling; expiry stops future capture but never drops evidence. */
    static final long SUBSCRIPTION_LEASE_TICKS = 6_000L;

    private enum Health { READY, FUTURE_SCHEMA, CORRUPT }

    private Health health = Health.READY;
    private int unreadableSchema;
    private CompoundTag preservedUnreadable;
    private boolean unreadableRequiresFence;
    private UUID providerEpoch;
    private long revision;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, AckTombstone> ackTombstones = new LinkedHashMap<>();
    private final Map<String, Long> registeredConsumers = new LinkedHashMap<>();
    /** Loaded entries are durable by definition. Newly appended entries join only after disk verification. */
    private final Set<UUID> durable = new HashSet<>();

    boolean canAppend(long now) {
        prune(now);
        if (health == Health.READY && entries.size() >= MAX_RECEIPTS) {
            retireAcknowledgedUnderPressure(now);
        }
        return health == Health.READY && entries.size() < MAX_RECEIPTS;
    }

    boolean shouldCapture(long now) {
        return health == Health.READY ? !activeConsumers(now).isEmpty() : unreadableRequiresFence;
    }

    boolean hasActiveConsumer(ResourceLocation consumer, long now) {
        return health == Health.READY && consumer != null
                && activeConsumers(now).contains(consumer.toString());
    }

    QuestCompletionReceipt append(UUID playerId, ActiveQuest active, long completedGameTime) {
        if (!canAppend(completedGameTime)) {
            throw new IllegalStateException("completion receipt outbox is not writable: " + status());
        }
        Set<String> intendedConsumers = activeConsumers(completedGameTime);
        if (intendedConsumers.isEmpty()) {
            throw new IllegalStateException("completion receipt outbox has no active consumer subscription");
        }
        if (entries.containsKey(active.instance()) || ackTombstones.containsKey(active.instance())) {
            throw new IllegalStateException("duplicate completion receipt id " + active.instance());
        }
        if (providerEpoch == null) providerEpoch = UUID.randomUUID();
        if (revision == Long.MAX_VALUE) {
            health = Health.CORRUPT;
            throw new IllegalStateException("completion receipt revision exhausted");
        }
        QuestCompletionReceipt receipt = new QuestCompletionReceipt(providerEpoch, active.instance(), playerId,
                active.questId(), ++revision, QuestCompletionReceipt.Outcome.COMPLETED,
                completedGameTime, active.startGameTime(), active.villagerUuid(), active.dimension(),
                active.villageId().isPresent() ? Optional.of(active.villageId().getAsInt()) : Optional.empty(),
                active.kingdomBinding().map(binding -> new QuestCompletionReceipt.KingdomBinding(
                        binding.settlementId(), binding.kingdomId(), binding.settlementRevision(),
                        binding.dimension(), binding.localDimension(),
                        binding.localVillageId().isPresent()
                                ? Optional.of(binding.localVillageId().getAsInt()) : Optional.empty())),
                active.civicBuildingBinding().map(binding -> new QuestCompletionReceipt.CivicBuildingBinding(
                        binding.bindingId(), binding.settlementId(), binding.dimension(), binding.villageId(),
                        binding.buildingId(), binding.family(), binding.typeAtBinding())),
                active.institutionalBinding());
        if (entries.putIfAbsent(receipt.receiptId(), new Entry(receipt, intendedConsumers)) != null) {
            throw new IllegalStateException("duplicate completion receipt id " + receipt.receiptId());
        }
        return receipt;
    }

    List<QuestCompletionReceipt> read(ResourceLocation consumer, int limit, long now) {
        if (health != Health.READY) return List.of();
        String id = consumer.toString();
        if (!registeredConsumers.containsKey(id) && registeredConsumers.size() >= MAX_CONSUMERS) {
            throw new IllegalStateException("completion receipt consumer capacity reached");
        }
        registeredConsumers.put(id, now);
        List<QuestCompletionReceipt> result = new ArrayList<>(Math.min(limit, entries.size()));
        for (Entry entry : entries.values()) {
            if (entry.intendedConsumers.contains(id) && durable.contains(entry.receipt.receiptId())
                    && !entry.acknowledgedBy.contains(id)) {
                result.add(entry.receipt);
                if (result.size() == limit) break;
            }
        }
        return List.copyOf(result);
    }

    boolean acknowledge(ResourceLocation consumer, UUID epoch, UUID receiptId) {
        if (health != Health.READY || providerEpoch == null || !providerEpoch.equals(epoch)) return false;
        Entry entry = entries.get(receiptId);
        String id = consumer.toString();
        if (entry == null) {
            AckTombstone tombstone = ackTombstones.get(receiptId);
            return tombstone != null && tombstone.intendedConsumers.contains(id);
        }
        if (!durable.contains(receiptId)) return false;
        if (!entry.intendedConsumers.contains(id)) return false;
        entry.acknowledgedBy.add(id);
        return true;
    }

    void rollbackAcknowledgement(ResourceLocation consumer, UUID receiptId, boolean previouslyAcknowledged) {
        Entry entry = entries.get(receiptId);
        if (entry == null || previouslyAcknowledged) return;
        entry.acknowledgedBy.remove(consumer.toString());
    }

    boolean isAcknowledged(ResourceLocation consumer, UUID epoch, UUID receiptId) {
        Entry entry = entries.get(receiptId);
        return health == Health.READY && providerEpoch != null && providerEpoch.equals(epoch)
                && (entry != null && entry.acknowledgedBy.contains(consumer.toString())
                || entry == null && Optional.ofNullable(ackTombstones.get(receiptId))
                .map(tombstone -> tombstone.intendedConsumers.contains(consumer.toString())).orElse(false));
    }

    boolean wasAcknowledged(ResourceLocation consumer, UUID receiptId) {
        Entry entry = entries.get(receiptId);
        return entry != null && entry.acknowledgedBy.contains(consumer.toString())
                || entry == null && Optional.ofNullable(ackTombstones.get(receiptId))
                .map(tombstone -> tombstone.intendedConsumers.contains(consumer.toString())).orElse(false);
    }

    void markDurable(UUID receiptId) {
        if (entries.containsKey(receiptId)) durable.add(receiptId);
    }

    Optional<QuestCompletionReceipt> receipt(UUID receiptId) {
        Entry entry = entries.get(receiptId);
        return entry == null ? Optional.empty() : Optional.of(entry.receipt);
    }

    boolean isDurable(UUID receiptId) {
        return durable.contains(receiptId);
    }

    List<QuestCompletionReceipt> pendingDurability() {
        if (health != Health.READY) return List.of();
        return entries.values().stream().map(entry -> entry.receipt)
                .filter(receipt -> !durable.contains(receipt.receiptId())).toList();
    }

    String status() {
        if (health == Health.FUTURE_SCHEMA) return "future_schema:" + unreadableSchema;
        if (health == Health.CORRUPT) return "corrupt";
        if (entries.size() >= MAX_RECEIPTS) return "full";
        if (durable.size() < entries.size()) return "pending_save";
        return "ready";
    }

    int size() {
        return entries.size();
    }

    boolean shouldSave() {
        return providerEpoch != null || preservedUnreadable != null || !entries.isEmpty()
                || !ackTombstones.isEmpty() || !registeredConsumers.isEmpty();
    }

    private void prune(long now) {
        if (health != Health.READY || registeredConsumers.isEmpty()) return;
        entries.entrySet().removeIf(value -> {
            Entry entry = value.getValue();
            boolean expired = now >= entry.receipt.completedGameTime()
                    && now - entry.receipt.completedGameTime() >= ACKNOWLEDGED_RETENTION_TICKS;
            if (expired && entry.acknowledgedBy.containsAll(entry.intendedConsumers)) {
                durable.remove(value.getKey());
                return true;
            }
            return false;
        });
        ackTombstones.entrySet().removeIf(value -> now >= value.getValue().expiresAt);
    }

    private void retireAcknowledgedUnderPressure(long now) {
        var iterator = entries.entrySet().iterator();
        while (entries.size() >= MAX_RECEIPTS && iterator.hasNext()) {
            Map.Entry<UUID, Entry> value = iterator.next();
            Entry entry = value.getValue();
            if (!entry.acknowledgedBy.containsAll(entry.intendedConsumers)) continue;
            long expiresAt = saturatedAdd(entry.receipt.completedGameTime(), ACKNOWLEDGED_RETENTION_TICKS);
            if (now < expiresAt) addTombstone(new AckTombstone(value.getKey(), expiresAt,
                    entry.intendedConsumers), now);
            durable.remove(value.getKey());
            iterator.remove();
        }
    }

    private void addTombstone(AckTombstone tombstone, long now) {
        ackTombstones.entrySet().removeIf(value -> now >= value.getValue().expiresAt);
        while (ackTombstones.size() >= MAX_ACK_TOMBSTONES) {
            UUID oldest = ackTombstones.keySet().iterator().next();
            ackTombstones.remove(oldest);
        }
        ackTombstones.put(tombstone.receiptId, tombstone);
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    CompoundTag save() {
        if (preservedUnreadable != null) return preservedUnreadable.copy();
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        if (providerEpoch != null) tag.putUUID("provider_epoch", providerEpoch);
        tag.putLong("revision", revision);
        ListTag receipts = new ListTag();
        entries.values().forEach(entry -> receipts.add(entry.save()));
        tag.put("receipts", receipts);
        ListTag tombstones = new ListTag();
        ackTombstones.values().forEach(value -> tombstones.add(value.save()));
        tag.put("ack_tombstones", tombstones);
        ListTag consumers = new ListTag();
        registeredConsumers.keySet().forEach(value -> consumers.add(StringTag.valueOf(value)));
        tag.put("consumers", consumers);
        ListTag subscriptions = new ListTag();
        registeredConsumers.forEach((id, lastSeen) -> {
            CompoundTag subscription = new CompoundTag();
            subscription.putString("id", id);
            subscription.putLong("last_seen", lastSeen);
            subscriptions.add(subscription);
        });
        tag.put("subscriptions", subscriptions);
        return tag;
    }

    void load(CompoundTag tag) {
        clear();
        if (tag == null || tag.isEmpty()) return;
        int schema = tag.getInt("schema");
        if (schema > SCHEMA) {
            health = Health.FUTURE_SCHEMA;
            unreadableSchema = schema;
            preservedUnreadable = tag.copy();
            // A future writer may have renamed its cohort fields. Never route around state we cannot
            // understand: doing so could publish completion without evidence owed to a known consumer.
            unreadableRequiresFence = true;
            return;
        }
        if (schema < 1 || schema > SCHEMA) {
            markCorrupt(tag);
            return;
        }
        try {
            UUID loadedEpoch = tag.hasUUID("provider_epoch") ? tag.getUUID("provider_epoch") : null;
            long loadedRevision = tag.getLong("revision");
            if (loadedRevision < 0L || (loadedRevision > 0L) != (loadedEpoch != null)) {
                throw new IllegalArgumentException("invalid epoch/revision");
            }
            LinkedHashMap<UUID, Entry> loadedEntries = new LinkedHashMap<>();
            long greatestRevision = 0L;
            ListTag receipts = tag.getList("receipts", Tag.TAG_COMPOUND);
            if (receipts.size() > MAX_RECEIPTS) throw new IllegalArgumentException("receipt capacity exceeded");
            for (int i = 0; i < receipts.size(); i++) {
                Entry entry = Entry.load(receipts.getCompound(i));
                QuestCompletionReceipt receipt = entry.receipt;
                if (loadedEpoch == null || !loadedEpoch.equals(receipt.providerEpoch())
                        || loadedEntries.putIfAbsent(receipt.receiptId(), entry) != null) {
                    throw new IllegalArgumentException("receipt epoch or identity mismatch");
                }
                greatestRevision = Math.max(greatestRevision, receipt.completionRevision());
            }
            if (greatestRevision > loadedRevision) throw new IllegalArgumentException("revision moved backwards");
            LinkedHashMap<UUID, AckTombstone> loadedTombstones = new LinkedHashMap<>();
            if (schema >= 3) {
                ListTag tombstones = tag.getList("ack_tombstones", Tag.TAG_COMPOUND);
                if (tombstones.size() > MAX_ACK_TOMBSTONES) {
                    throw new IllegalArgumentException("ack tombstone capacity exceeded");
                }
                for (int i = 0; i < tombstones.size(); i++) {
                    AckTombstone tombstone = AckTombstone.load(tombstones.getCompound(i));
                    if (loadedEntries.containsKey(tombstone.receiptId)
                            || loadedTombstones.putIfAbsent(tombstone.receiptId, tombstone) != null) {
                        throw new IllegalArgumentException("duplicate ack tombstone identity");
                    }
                }
            }
            LinkedHashMap<String, Long> loadedConsumers = new LinkedHashMap<>();
            ListTag consumers = tag.getList("consumers", Tag.TAG_STRING);
            if (consumers.size() > MAX_CONSUMERS) throw new IllegalArgumentException("consumer capacity exceeded");
            for (int i = 0; i < consumers.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(consumers.getString(i));
                if (id == null || !id.toString().equals(consumers.getString(i))) {
                    throw new IllegalArgumentException("invalid consumer id");
                }
                loadedConsumers.put(id.toString(), Long.MIN_VALUE);
            }
            if (schema >= 2) {
                ListTag subscriptions = tag.getList("subscriptions", Tag.TAG_COMPOUND);
                if (subscriptions.size() != loadedConsumers.size()) {
                    throw new IllegalArgumentException("consumer subscription mismatch");
                }
                for (int i = 0; i < subscriptions.size(); i++) {
                    CompoundTag subscription = subscriptions.getCompound(i);
                    ResourceLocation id = ResourceLocation.tryParse(subscription.getString("id"));
                    long lastSeen = subscription.getLong("last_seen");
                    if (id == null || !id.toString().equals(subscription.getString("id"))
                            || !loadedConsumers.containsKey(id.toString()) || lastSeen < 0L) {
                        throw new IllegalArgumentException("invalid consumer subscription");
                    }
                    loadedConsumers.put(id.toString(), lastSeen);
                }
            }
            for (Entry entry : loadedEntries.values()) {
                if (schema == 1) entry.intendedConsumers.addAll(loadedConsumers.keySet());
                if (!loadedConsumers.keySet().containsAll(entry.intendedConsumers)
                        || !entry.intendedConsumers.containsAll(entry.acknowledgedBy)) {
                    throw new IllegalArgumentException("acknowledgement names an unregistered consumer");
                }
            }
            for (AckTombstone tombstone : loadedTombstones.values()) {
                if (!loadedConsumers.keySet().containsAll(tombstone.intendedConsumers)) {
                    throw new IllegalArgumentException("ack tombstone names an unregistered consumer");
                }
            }
            providerEpoch = loadedEpoch;
            revision = loadedRevision;
            entries.putAll(loadedEntries);
            ackTombstones.putAll(loadedTombstones);
            registeredConsumers.putAll(loadedConsumers);
            durable.addAll(loadedEntries.keySet());
        } catch (RuntimeException malformed) {
            clear();
            markCorrupt(tag);
        }
    }

    private void clear() {
        health = Health.READY;
        unreadableSchema = 0;
        preservedUnreadable = null;
        unreadableRequiresFence = false;
        providerEpoch = null;
        revision = 0L;
        entries.clear();
        ackTombstones.clear();
        registeredConsumers.clear();
        durable.clear();
    }

    private void markCorrupt(CompoundTag tag) {
        health = Health.CORRUPT;
        preservedUnreadable = tag.copy();
        unreadableRequiresFence = nonEmptyOrMalformedList(tag, "receipts")
                || nonEmptyOrMalformedList(tag, "ack_tombstones")
                || nonEmptyOrMalformedList(tag, "consumers")
                || nonEmptyOrMalformedList(tag, "subscriptions");
    }

    private static boolean nonEmptyOrMalformedList(CompoundTag tag, String key) {
        if (!tag.contains(key)) return false;
        Tag value = tag.get(key);
        return !(value instanceof ListTag list) || !list.isEmpty();
    }

    private Set<String> activeConsumers(long now) {
        Set<String> active = new LinkedHashSet<>();
        registeredConsumers.forEach((id, lastSeen) -> {
            if (lastSeen >= 0L && now >= lastSeen && now - lastSeen <= SUBSCRIPTION_LEASE_TICKS) {
                active.add(id);
            }
        });
        return active;
    }

    private static final class Entry {
        private final QuestCompletionReceipt receipt;
        private final Set<String> intendedConsumers = new LinkedHashSet<>();
        private final Set<String> acknowledgedBy = new LinkedHashSet<>();

        private Entry(QuestCompletionReceipt receipt, Set<String> intendedConsumers) {
            this.receipt = receipt;
            this.intendedConsumers.addAll(intendedConsumers);
        }

        private CompoundTag save() {
            CompoundTag tag = saveReceipt(receipt);
            ListTag acknowledgements = new ListTag();
            acknowledgedBy.forEach(value -> acknowledgements.add(StringTag.valueOf(value)));
            tag.put("acknowledged_by", acknowledgements);
            ListTag intended = new ListTag();
            intendedConsumers.forEach(value -> intended.add(StringTag.valueOf(value)));
            tag.put("intended_for", intended);
            return tag;
        }

        private static Entry load(CompoundTag tag) {
            Entry entry = new Entry(loadReceipt(tag), Set.of());
            ListTag intended = tag.getList("intended_for", Tag.TAG_STRING);
            if (intended.size() > MAX_CONSUMERS) {
                throw new IllegalArgumentException("intended consumer capacity exceeded");
            }
            for (int i = 0; i < intended.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(intended.getString(i));
                if (id == null || !id.toString().equals(intended.getString(i))) {
                    throw new IllegalArgumentException("invalid intended consumer id");
                }
                entry.intendedConsumers.add(id.toString());
            }
            ListTag acknowledgements = tag.getList("acknowledged_by", Tag.TAG_STRING);
            if (acknowledgements.size() > MAX_CONSUMERS) {
                throw new IllegalArgumentException("acknowledgement capacity exceeded");
            }
            for (int i = 0; i < acknowledgements.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(acknowledgements.getString(i));
                if (id == null || !id.toString().equals(acknowledgements.getString(i))) {
                    throw new IllegalArgumentException("invalid acknowledgement consumer id");
                }
                entry.acknowledgedBy.add(id.toString());
            }
            return entry;
        }
    }

    private static final class AckTombstone {
        private final UUID receiptId;
        private final long expiresAt;
        private final Set<String> intendedConsumers = new LinkedHashSet<>();

        private AckTombstone(UUID receiptId, long expiresAt, Set<String> intendedConsumers) {
            this.receiptId = receiptId;
            this.expiresAt = expiresAt;
            this.intendedConsumers.addAll(intendedConsumers);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("receipt_id", receiptId);
            tag.putLong("expires_at", expiresAt);
            ListTag intended = new ListTag();
            intendedConsumers.forEach(value -> intended.add(StringTag.valueOf(value)));
            tag.put("intended_for", intended);
            return tag;
        }

        private static AckTombstone load(CompoundTag tag) {
            if (!tag.hasUUID("receipt_id") || tag.getLong("expires_at") < 0L) {
                throw new IllegalArgumentException("invalid ack tombstone");
            }
            Set<String> intended = readConsumerIds(tag.getList("intended_for", Tag.TAG_STRING),
                    "ack tombstone");
            if (intended.isEmpty()) throw new IllegalArgumentException("empty ack tombstone cohort");
            return new AckTombstone(tag.getUUID("receipt_id"), tag.getLong("expires_at"), intended);
        }
    }

    private static Set<String> readConsumerIds(ListTag values, String what) {
        if (values.size() > MAX_CONSUMERS) throw new IllegalArgumentException(what + " capacity exceeded");
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(values.getString(i));
            if (id == null || !id.toString().equals(values.getString(i))) {
                throw new IllegalArgumentException("invalid " + what + " consumer id");
            }
            result.add(id.toString());
        }
        return result;
    }

    private static CompoundTag saveReceipt(QuestCompletionReceipt receipt) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("provider_epoch", receipt.providerEpoch());
        tag.putUUID("receipt_id", receipt.receiptId());
        tag.putUUID("player_id", receipt.playerId());
        tag.putString("quest_id", receipt.questId().toString());
        tag.putLong("revision", receipt.completionRevision());
        tag.putString("outcome", receipt.outcome().name());
        tag.putLong("completed", receipt.completedGameTime());
        tag.putLong("accepted", receipt.acceptedGameTime());
        tag.putUUID("giver_id", receipt.giverId());
        tag.putString("accepted_dimension", receipt.acceptedDimension().toString());
        receipt.acceptedVillageId().ifPresent(value -> tag.putInt("accepted_village", value));
        receipt.kingdomBinding().ifPresent(value -> tag.put("kingdom_binding", saveKingdom(value)));
        receipt.civicBuildingBinding().ifPresent(value -> tag.put("civic_building_binding", saveCivic(value)));
        if (!receipt.institutionalBinding().isEmpty()) {
            tag.putString("institutional_binding", receipt.institutionalBinding());
        }
        return tag;
    }

    private static QuestCompletionReceipt loadReceipt(CompoundTag tag) {
        if (!tag.hasUUID("provider_epoch") || !tag.hasUUID("receipt_id") || !tag.hasUUID("player_id")
                || !tag.hasUUID("giver_id")) throw new IllegalArgumentException("missing receipt UUID");
        ResourceLocation quest = strictId(tag.getString("quest_id"));
        ResourceLocation dimension = strictId(tag.getString("accepted_dimension"));
        QuestCompletionReceipt.Outcome outcome = QuestCompletionReceipt.Outcome.valueOf(tag.getString("outcome"));
        Optional<Integer> village = tag.contains("accepted_village", Tag.TAG_INT)
                ? Optional.of(tag.getInt("accepted_village")) : Optional.empty();
        Optional<QuestCompletionReceipt.KingdomBinding> kingdom = tag.contains("kingdom_binding", Tag.TAG_COMPOUND)
                ? Optional.of(loadKingdom(tag.getCompound("kingdom_binding"))) : Optional.empty();
        Optional<QuestCompletionReceipt.CivicBuildingBinding> civic =
                tag.contains("civic_building_binding", Tag.TAG_COMPOUND)
                        ? Optional.of(loadCivic(tag.getCompound("civic_building_binding"))) : Optional.empty();
        return new QuestCompletionReceipt(tag.getUUID("provider_epoch"), tag.getUUID("receipt_id"),
                tag.getUUID("player_id"), quest, tag.getLong("revision"), outcome,
                tag.getLong("completed"), tag.getLong("accepted"), tag.getUUID("giver_id"), dimension,
                village, kingdom, civic, tag.getString("institutional_binding"));
    }

    private static CompoundTag saveKingdom(QuestCompletionReceipt.KingdomBinding value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("settlement_id", value.settlementId());
        tag.putString("kingdom_id", value.kingdomId().toString());
        tag.putLong("settlement_revision", value.settlementRevision());
        tag.putString("dimension", value.dimension().toString());
        value.localDimension().ifPresent(dimension -> tag.putString("local_dimension", dimension.toString()));
        value.localVillageId().ifPresent(village -> tag.putInt("local_village", village));
        return tag;
    }

    private static QuestCompletionReceipt.KingdomBinding loadKingdom(CompoundTag tag) {
        if (!tag.hasUUID("settlement_id")) throw new IllegalArgumentException("missing settlement id");
        Optional<ResourceLocation> localDimension = tag.contains("local_dimension", Tag.TAG_STRING)
                ? Optional.of(strictId(tag.getString("local_dimension"))) : Optional.empty();
        Optional<Integer> localVillage = tag.contains("local_village", Tag.TAG_INT)
                ? Optional.of(tag.getInt("local_village")) : Optional.empty();
        return new QuestCompletionReceipt.KingdomBinding(tag.getUUID("settlement_id"),
                strictId(tag.getString("kingdom_id")), tag.getLong("settlement_revision"),
                strictId(tag.getString("dimension")), localDimension, localVillage);
    }

    private static CompoundTag saveCivic(QuestCompletionReceipt.CivicBuildingBinding value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("binding_id", value.bindingId());
        tag.putUUID("settlement_id", value.settlementId());
        tag.putString("dimension", value.dimension().toString());
        tag.putInt("village", value.villageId());
        tag.putInt("building", value.buildingId());
        tag.putString("family", value.family());
        tag.putString("type", value.typeAtBinding());
        return tag;
    }

    private static QuestCompletionReceipt.CivicBuildingBinding loadCivic(CompoundTag tag) {
        if (!tag.hasUUID("binding_id") || !tag.hasUUID("settlement_id")) {
            throw new IllegalArgumentException("missing civic binding UUID");
        }
        return new QuestCompletionReceipt.CivicBuildingBinding(tag.getUUID("binding_id"),
                tag.getUUID("settlement_id"), strictId(tag.getString("dimension")), tag.getInt("village"),
                tag.getInt("building"), tag.getString("family"), tag.getString("type"));
    }

    private static ResourceLocation strictId(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !id.toString().equals(value)) throw new IllegalArgumentException("invalid resource id");
        return id;
    }
}
