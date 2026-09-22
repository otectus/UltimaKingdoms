package dev.otectus.mcaquests.state;

import dev.otectus.mcaquests.quest.template.ResolvedTemplate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One villager's current offer set for one player: what was drawn, what it resolved to, and what the
 * player has already refused.
 *
 * <p>This is the piece the mod was missing, and both halves of the reported decline bug follow from its
 * absence. The offer menu was recomputed from scratch on every open — eligibility pass, weighted draw,
 * template resolution, dialogue resolution — and the draw was a pure function of (player, villager, world
 * day). So declining changed nothing about the inputs, the same three quests came straight back, and there
 * was nowhere for "I already turned that one down" to live.
 *
 * <p>Persisting the set fixes more than the decline. It also means:
 *
 * <ul>
 *   <li><b>The other offers do not move.</b> {@code WeightedPicker} draws sequentially from a shrinking
 *   pool, so removing one candidate changes every subsequent draw. Re-running selection with the declined
 *   quest filtered out would have replaced the whole menu. Remembering the set and swapping one slot
 *   replaces exactly the card you turned down.</li>
 *   <li><b>The story stops changing.</b> Offer dialogue is resolved once, here, at draw time. It used to
 *   be resolved per card per render, so with MCA: Conversations installed a villager re-voiced all three
 *   offers every time the menu was reopened — which is what the reporter was watching.</li>
 *   <li><b>Template values are the values you were shown.</b> Frozen at draw and read at accept, rather
 *   than re-derived from a day-stable seed that stops agreeing the moment the world day ticks over
 *   between the menu opening and the button being clicked.</li>
 * </ul>
 *
 * <p>The same contract the mod already gives randomized rewards, extended to the whole offer: what the
 * villager said is what the villager said.
 */
public final class OfferSession {

    /**
     * The stored deadline meaning "until this villager's offers next refresh".
     *
     * <p>Needed because the natural encoding does not work: with {@code declineCooldownTicks} at its
     * default of 0, storing {@code now + 0} makes the refusal lapse on the very same tick it was recorded,
     * so declining would appear to do nothing all over again. A sentinel says plainly that this refusal is
     * tied to the offer set rather than to the clock — {@link #redraw} drops these and leaves timed ones
     * alone, which is what lets a configured cooldown outlive the set it was given in.
     */
    private static final long UNTIL_REFRESH = 0L;

    private final UUID villagerUuid;
    private long refreshedAtGameTime;
    private int packGeneration;
    private long seed;
    private final List<Slot> slots = new ArrayList<>();
    /** Null for the ordinary villager repertoire; otherwise the trusted commission API's bounded scope. */
    @Nullable
    private Set<ResourceLocation> restrictedQuestIds;
    /** Quest id (as a string, because that is what NBT keys are) to the game time the refusal lapses. */
    private final Map<String, Long> declinedUntil = new HashMap<>();

    /**
     * One card's worth of remembered offer.
     *
     * @param questId      the quest drawn into this slot
     * @param frozenValues the template variables it resolved to, or {@code null} for a non-template quest
     * @param voicedOffer  the offer line as it was voiced at draw time, or {@code null} to render it live
     */
    public record Slot(ResourceLocation questId,
                       @Nullable ResolvedTemplate frozenValues,
                       @Nullable Component voicedOffer) {
    }

    public OfferSession(UUID villagerUuid) {
        this.villagerUuid = villagerUuid;
    }

    public UUID villagerUuid() {
        return villagerUuid;
    }

    public long refreshedAtGameTime() {
        return refreshedAtGameTime;
    }

    public int packGeneration() {
        return packGeneration;
    }

    public long seed() {
        return seed;
    }

    /** The drawn slots, in menu order. Mutable so one card can be replaced without disturbing the rest. */
    public List<Slot> slots() {
        return slots;
    }

    /**
     * Replaces the whole set — a fresh draw.
     *
     * <p>Refusals that were only meant to last as long as the old set are forgotten here; ones given an
     * explicit {@code declineCooldownTicks} are not, because a configured cooldown is a statement about
     * time rather than about this particular menu.
     */
    public void redraw(List<Slot> drawn, long gameTime, int generation, long drawSeed) {
        redraw(drawn, gameTime, generation, drawSeed, null);
    }

    /** Replaces the set and records which public menu surface drew it. */
    public void redraw(List<Slot> drawn, long gameTime, int generation, long drawSeed,
                       @Nullable Set<ResourceLocation> restriction) {
        declinedUntil.values().removeIf(until -> until == UNTIL_REFRESH);
        slots.clear();
        slots.addAll(drawn);
        refreshedAtGameTime = gameTime;
        packGeneration = generation;
        seed = drawSeed;
        restrictedQuestIds = restriction == null ? null : Set.copyOf(restriction);
    }

    public boolean scopeMatches(@Nullable Set<ResourceLocation> restriction) {
        return java.util.Objects.equals(restrictedQuestIds, restriction);
    }

    public boolean allowsInCurrentScope(ResourceLocation questId) {
        return restrictedQuestIds == null || restrictedQuestIds.contains(questId);
    }

    public Optional<Set<ResourceLocation>> restrictedQuestIds() {
        return Optional.ofNullable(restrictedQuestIds);
    }

    /**
     * Records a refusal.
     *
     * <p>{@code cooldownTicks <= 0} means "until this villager has something new to say", which is the
     * default and the gentler reading: turning an offer down should not lock it away for a week.
     */
    public void decline(ResourceLocation questId, long now, int cooldownTicks) {
        declinedUntil.put(questId.toString(),
                cooldownTicks <= 0 ? UNTIL_REFRESH : now + cooldownTicks);
    }

    /**
     * Removes the slot holding {@code questId}, returning the index it occupied, or {@code -1} if this
     * villager was not offering it.
     *
     * <p>The {@code -1} is the client-trust check: a decline packet can name any quest id at all, and only
     * one that is genuinely on this player's menu for this villager may do anything. Removing by index
     * rather than rebuilding the list is also what keeps the promise that the other cards do not move.
     */
    public int removeSlot(ResourceLocation questId) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).questId().equals(questId)) {
                slots.remove(i);
                return i;
            }
        }
        return -1;
    }

    /** Whether this quest is currently refused, and so must not be drawn back into a slot. */
    public boolean isDeclined(ResourceLocation questId, long now) {
        Long until = declinedUntil.get(questId.toString());
        return until != null && (until == UNTIL_REFRESH || now < until);
    }

    /**
     * Ticks until a refusal lapses, for the debug command. Empty when it is not refused, and empty for one
     * that lasts until the next refresh — that countdown is the session's, not the refusal's.
     */
    public Optional<Long> declineRemaining(ResourceLocation questId, long now) {
        Long until = declinedUntil.get(questId.toString());
        if (until == null || until == UNTIL_REFRESH || now >= until) {
            return Optional.empty();
        }
        return Optional.of(until - now);
    }

    /** A read-only view of the refusals, for the debug command. */
    public Map<String, Long> declinedView() {
        return Map.copyOf(declinedUntil);
    }

    /**
     * Whether any refusal here is tied to this offer set rather than to the clock.
     *
     * <p>Read by {@link #isStale}: a set the player has emptied by turning everything down is not an
     * absent set, it is an answered one, and drawing it again would bring back the very quests they just
     * refused — the 1.4.3 decline fix, undone by the empty list.
     */
    public boolean hasUntilRefreshRefusals() {
        return declinedUntil.containsValue(UNTIL_REFRESH);
    }

    /**
     * Whether a refusal here still has time left on an explicit {@code declineCooldownTicks}.
     *
     * <p>Session pruning asks this before discarding a set nobody has looked at: a cooldown configured
     * longer than the pruning horizon is a statement about time, and dropping the session that holds it
     * would cut it short.
     */
    public boolean hasTimedRefusalAfter(long now) {
        return declinedUntil.values().stream().anyMatch(until -> until != UNTIL_REFRESH && now < until);
    }

    /** Drops timed refusals whose time has passed, so the map cannot grow without bound. */
    public void pruneDeclines(long now) {
        declinedUntil.entrySet().removeIf(entry -> entry.getValue() != UNTIL_REFRESH
                && now >= entry.getValue());
    }

    /** True when this set is old enough, or stale enough, that it must be drawn again. */
    public boolean isStale(long now, int refreshTicks, int generation) {
        // An empty set is normally one that was never drawn — but it is also what is left when the player
        // declines every card, and that emptiness must not read as "draw the same three again".
        return (slots.isEmpty() && !hasUntilRefreshRefusals())
                || packGeneration != generation
                // Guards a backwards clock as well as an elapsed one: a world restored from a backup can
                // put "now" behind the stamp, and a session that could never expire would be worse than
                // one that expires early.
                || now < refreshedAtGameTime
                || now - refreshedAtGameTime >= refreshTicks;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("villager", villagerUuid);
        tag.putLong("refreshed", refreshedAtGameTime);
        tag.putInt("generation", packGeneration);
        tag.putLong("seed", seed);
        if (restrictedQuestIds != null) {
            ListTag restricted = new ListTag();
            restrictedQuestIds.stream().map(ResourceLocation::toString).sorted()
                    .forEach(value -> restricted.add(net.minecraft.nbt.StringTag.valueOf(value)));
            tag.put("restricted_quests", restricted);
        }
        ListTag list = new ListTag();
        for (Slot slot : slots) {
            CompoundTag entry = new CompoundTag();
            entry.putString("quest", slot.questId().toString());
            if (slot.frozenValues() != null) {
                entry.put("template", slot.frozenValues().save());
            }
            if (slot.voicedOffer() != null) {
                entry.putString("voiced", Component.Serializer.toJson(slot.voicedOffer()));
            }
            list.add(entry);
        }
        tag.put("slots", list);
        if (!declinedUntil.isEmpty()) {
            CompoundTag declined = new CompoundTag();
            declinedUntil.forEach(declined::putLong);
            tag.put("declined", declined);
        }
        return tag;
    }

    /**
     * Reads a session back.
     *
     * <p>A slot whose quest id no longer parses is dropped rather than failing the load: a session is a
     * convenience, and losing one costs the player a reroll, where refusing to load their save costs them
     * everything.
     */
    public static OfferSession load(CompoundTag tag) {
        OfferSession session = new OfferSession(tag.getUUID("villager"));
        session.refreshedAtGameTime = tag.getLong("refreshed");
        session.packGeneration = tag.getInt("generation");
        session.seed = tag.getLong("seed");
        if (tag.contains("restricted_quests", Tag.TAG_LIST)) {
            Set<ResourceLocation> restricted = new java.util.LinkedHashSet<>();
            ListTag values = tag.getList("restricted_quests", Tag.TAG_STRING);
            for (int i = 0; i < values.size() && i < 32; i++) {
                ResourceLocation id = ResourceLocation.tryParse(values.getString(i));
                if (id != null) restricted.add(id);
            }
            session.restrictedQuestIds = Set.copyOf(restricted);
        }
        ListTag list = tag.getList("slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation questId = ResourceLocation.tryParse(entry.getString("quest"));
            if (questId == null) {
                continue;
            }
            ResolvedTemplate template = entry.contains("template")
                    ? ResolvedTemplate.load(entry.getCompound("template")) : null;
            Component voiced = null;
            if (entry.contains("voiced")) {
                try {
                    voiced = Component.Serializer.fromJson(entry.getString("voiced"));
                } catch (RuntimeException ignored) {
                    voiced = null; // re-voice it live rather than lose the slot
                }
            }
            session.slots.add(new Slot(questId, template, voiced));
        }
        CompoundTag declined = tag.getCompound("declined");
        declined.getAllKeys().forEach(key -> session.declinedUntil.put(key, declined.getLong(key)));
        return session;
    }

    public void copyFrom(OfferSession other) {
        refreshedAtGameTime = other.refreshedAtGameTime;
        packGeneration = other.packGeneration;
        seed = other.seed;
        restrictedQuestIds = other.restrictedQuestIds == null ? null : Set.copyOf(other.restrictedQuestIds);
        slots.clear();
        slots.addAll(other.slots);
        declinedUntil.clear();
        declinedUntil.putAll(other.declinedUntil);
    }
}
