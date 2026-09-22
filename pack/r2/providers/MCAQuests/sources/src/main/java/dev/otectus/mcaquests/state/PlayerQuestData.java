package dev.otectus.mcaquests.state;

import dev.otectus.mcaquests.api.QuestCompletionReceipt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All of a player's MCA quest state — active quests, history, titles, progression stats and the offers
 * each villager is currently showing them — held in a Forge capability and serialised to the player's NBT
 * (spec section 16). Server-authoritative; never trust a client copy.
 */
public final class PlayerQuestData {

    private final List<ActiveQuest> active = new ArrayList<>();
    /** Unreadable entries survive a save for recovery without preventing the player from logging in. */
    private final List<CompoundTag> unreadableActive = new ArrayList<>();
    private final QuestHistory history = new QuestHistory();
    private final PlayerTitles titles = new PlayerTitles();
    private final ProgressionStats stats = new ProgressionStats();
    private final OfferSessions offers = new OfferSessions();
    private final PendingItemRewards pendingItems = new PendingItemRewards();
    private final CompletionReceiptOutbox completionReceipts = new CompletionReceiptOutbox();

    public PendingItemRewards pendingItems() { return pendingItems; }

    /** Internal completion pipeline hook. Add-ons read the immutable API through {@code McaQuestsApi}. */
    public boolean canCaptureCompletionReceipt(long now) {
        return completionReceipts.canAppend(now);
    }

    /** True only while at least one add-on is actively polling for future completion evidence. */
    public boolean shouldCaptureCompletionReceipt(long now) {
        return completionReceipts.shouldCapture(now);
    }

    /** True only while this exact consumer will be frozen into a newly appended receipt's cohort. */
    public boolean hasActiveCompletionReceiptConsumer(ResourceLocation consumer, long now) {
        return completionReceipts.hasActiveConsumer(consumer, now);
    }

    /** Internal completion pipeline hook; the returned receipt is hidden until marked durable. */
    public QuestCompletionReceipt captureCompletionReceipt(UUID playerId, ActiveQuest active, long now) {
        return completionReceipts.append(playerId, active, now);
    }

    public List<QuestCompletionReceipt> readCompletionReceipts(ResourceLocation consumer, int limit, long now) {
        return completionReceipts.read(consumer, limit, now);
    }

    public boolean acknowledgeCompletionReceipt(ResourceLocation consumer, UUID epoch, UUID receiptId) {
        return completionReceipts.acknowledge(consumer, epoch, receiptId);
    }

    public boolean completionReceiptWasAcknowledged(ResourceLocation consumer, UUID receiptId) {
        return completionReceipts.wasAcknowledged(consumer, receiptId);
    }

    public void rollbackCompletionReceiptAcknowledgement(ResourceLocation consumer, UUID receiptId,
                                                          boolean previouslyAcknowledged) {
        completionReceipts.rollbackAcknowledgement(consumer, receiptId, previouslyAcknowledged);
    }

    public boolean completionReceiptAcknowledged(ResourceLocation consumer, UUID epoch, UUID receiptId) {
        return completionReceipts.isAcknowledged(consumer, epoch, receiptId);
    }

    public void markCompletionReceiptDurable(UUID receiptId) {
        completionReceipts.markDurable(receiptId);
    }

    public Optional<QuestCompletionReceipt> completionReceipt(UUID receiptId) {
        return completionReceipts.receipt(receiptId);
    }

    public boolean completionReceiptDurable(UUID receiptId) {
        return completionReceipts.isDurable(receiptId);
    }

    public List<QuestCompletionReceipt> pendingCompletionReceiptDurability() {
        return completionReceipts.pendingDurability();
    }

    public String completionReceiptStatus() {
        return completionReceipts.status();
    }

    /** The quest the marker, the guidance line and the villager outline are all about. */
    private TrackedQuest tracked;

    public List<ActiveQuest> active() {
        return active;
    }

    public QuestHistory history() {
        return history;
    }

    public PlayerTitles titles() {
        return titles;
    }

    public ProgressionStats stats() {
        return stats;
    }

    /**
     * What each villager is currently offering this player, and what they have already turned down.
     *
     * <p>Lives with the player rather than the villager because an offer set is a fact about a pair: two
     * players talking to the same villager see, and refuse, different things.
     */
    public OfferSessions offers() {
        return offers;
    }

    /**
     * The quest this player is following, if it is still active.
     *
     * <p>Filtered against {@link #active} on every read rather than cleared when a quest ends, so no
     * caller has to remember to tidy up at the moment a quest completes, fails, is abandoned, or
     * disappears in a datapack reload. A dangling reference simply stops being an answer.
     */
    public Optional<TrackedQuest> tracked() {
        if (tracked == null) {
            return Optional.empty();
        }
        return active.stream().anyMatch(tracked::matches) ? Optional.of(tracked) : Optional.empty();
    }

    /** The active quest this player is following, if any. */
    public Optional<ActiveQuest> trackedQuest() {
        return tracked().flatMap(ref -> active.stream().filter(ref::matches).findFirst());
    }

    /** Follows {@code quest}; pass {@code null} to follow nothing. */
    public void setTracked(ActiveQuest quest) {
        tracked = quest == null ? null : new TrackedQuest(quest.questId(), quest.villagerUuid());
    }

    /** Follows {@code quest} only if nothing valid is being followed already. */
    public void trackIfNothingTracked(ActiveQuest quest) {
        if (tracked().isEmpty()) {
            setTracked(quest);
        }
    }

    /** True when {@code quest} is the one being followed. */
    public boolean isTracked(ActiveQuest quest) {
        return tracked().map(ref -> ref.matches(quest)).orElse(false);
    }

    public int activeCount() {
        return active.size();
    }

    public Optional<ActiveQuest> find(ResourceLocation questId, UUID villager) {
        return active.stream()
                .filter(q -> q.questId().equals(questId) && q.villagerUuid().equals(villager))
                .findFirst();
    }

    public List<ActiveQuest> byVillager(UUID villager) {
        return active.stream().filter(q -> q.villagerUuid().equals(villager)).toList();
    }

    public boolean hasActive(ResourceLocation questId, UUID villager) {
        return find(questId, villager).isPresent();
    }

    /**
     * True when this quest is active with <em>any</em> villager. A quest with no chain is one job, and
     * holding it from two givers at once used to pay it twice: the kill/craft/break events credit every
     * active copy, so one set of kills completed both. Arcs stay per-villager (see
     * {@code OfferFilters.alreadyActive}), which is why this is a second question rather than a change
     * to the one above.
     */
    public boolean hasActive(ResourceLocation questId) {
        return active.stream().anyMatch(q -> q.questId().equals(questId));
    }

    public void add(ActiveQuest quest) {
        active.add(quest);
    }

    public void remove(ActiveQuest quest) {
        active.remove(quest);
        if (tracked != null && tracked.matches(quest)) {
            tracked = null;
        }
    }

    public void copyFrom(PlayerQuestData other) {
        // Player cloning must not share mutable objective/offer state with the dead entity.
        load(other.save());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (ActiveQuest quest : active) {
            list.add(quest.save());
        }
        unreadableActive.forEach(entry -> list.add(entry.copy()));
        tag.put("active", list);
        tag.put("history", history.save());
        tag.put("titles", titles.save());
        tag.put("stats", stats.save());
        tag.put("offers", offers.save());
        if (!pendingItems.isEmpty()) { tag.put("pending_items", pendingItems.save()); }
        if (completionReceipts.shouldSave()) { tag.put("completion_receipts", completionReceipts.save()); }
        // Written only when something is tracked, so a save that never used the feature is byte-for-byte
        // what it was — the same discipline ActiveQuest applies to its own optional fields.
        if (tracked != null) {
            tag.put("tracked", tracked.save());
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        active.clear();
        unreadableActive.clear();
        ListTag list = tag.getList("active", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                active.add(ActiveQuest.load(entry));
            } catch (RuntimeException failure) {
                unreadableActive.add(entry.copy());
                dev.otectus.mcaquests.McaQuests.LOGGER.warn(
                        "[MCA: Quests] preserving unreadable active quest '{}' for recovery", entry.getString("quest"), failure);
            }
        }
        history.load(tag.getCompound("history"));
        titles.load(tag.getCompound("titles")); // absent on pre-0.7.0 saves -> empty
        stats.load(tag.getCompound("stats")); // absent on pre-1.0.0 saves -> empty
        offers.load(tag.getCompound("offers")); // absent on pre-1.4.3 saves -> empty, so offers redraw
        pendingItems.load(tag.getCompound("pending_items"));
        completionReceipts.load(tag.getCompound("completion_receipts"));
        // Absent on pre-1.5.0 saves -> nothing tracked, and the next quest accepted picks itself up.
        tracked = TrackedQuest.load(tag.getCompound("tracked")).orElse(null);
    }
}
