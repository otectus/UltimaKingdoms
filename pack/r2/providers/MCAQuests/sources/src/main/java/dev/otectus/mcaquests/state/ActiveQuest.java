package dev.otectus.mcaquests.state;

import dev.otectus.mcaquests.McaQuests;
import dev.otectus.mcaquests.compat.McaCompat;
import dev.otectus.mcaquests.data.QuestRegistry;
import dev.otectus.mcaquests.quest.QuestDefinition;
import dev.otectus.mcaquests.quest.InstitutionalCommissionBridge;
import dev.otectus.mcaquests.quest.objective.ObjectiveProgress;
import dev.otectus.mcaquests.quest.reputation.QuestReputation;
import dev.otectus.mcaquests.quest.template.PlaceholderResolver;
import dev.otectus.mcaquests.quest.target.FrozenLocation;
import dev.otectus.mcaquests.quest.template.ResolvedTemplate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One accepted, in-flight quest stored on the player (spec section 16). Holds a snapshot of the
 * giver's identity so the quest survives the villager unloading, moving, or dying.
 */
public final class ActiveQuest {

    private final ResourceLocation questId;
    private final UUID villagerUuid;
    private final Component villagerName;
    @Nullable
    private final ResourceLocation villagerProfession;
    private final ResourceLocation dimension;
    private final long startGameTime;
    /**
     * The world clock ({@code level.getDayTime()}) this quest was accepted at, for the
     * {@code deadline_time} trigger, which is documented as a time of day and so has to be measured on
     * the clock the player can see -- sleeping and {@code /time set} move it, game time never does.
     *
     * <p>Empty on quests accepted before 1.5.1, which keep their game-time deadline rather than having
     * it silently retargeted underneath a player who is already holding them.
     */
    private final OptionalLong startDayTime;
    /**
     * The giver's village, frozen at accept time, so hearts, reputation and village-scoped titles can
     * still be granted when the giver's chunk is not loaded -- the normal case for a quest that
     * completes in the field. Empty on quests accepted before 1.5.1 and on a giver who belonged to no
     * resolvable village; the reward path then falls back to a resident scan.
     */
    private final OptionalInt villageId;
    @Nullable
    private KingdomBindingSnapshot kingdomBinding;
    @Nullable
    private CivicBuildingBinding civicBuildingBinding;
    private final List<ObjectiveProgress> progress;
    /** Frozen template values for a quest accepted from a template (spec: chosen values must not reroll). */
    @Nullable
    private final ResolvedTemplate template;
    /**
     * The open situation this quest was accepted from (0.8.0), or {@code null} for an ordinary quest.
     * Links the per-player quest back to the village-shared {@code SituationInstance} so completion can
     * resolve it. Additive and optional — absent on pre-0.8.0 saves.
     */
    @Nullable
    private final UUID situationInstance;
    /**
     * Reward amounts rolled once at accept time, keyed by index into the quest's reward list — currently
     * only {@code mcaquests:currency}. Persisted so re-opening the menu, reconnecting, or reloading the
     * world all show and pay the same number; there is deliberately no code path that rolls a second time.
     * Absent on pre-1.1.0 saves and on quests with no randomized reward, in which case nothing is written.
     */
    private final Map<Integer, Integer> frozenRewards = new HashMap<>();

    /**
     * Destinations this quest has committed to, keyed by {@link LocationAnchor#fingerprint()}.
     *
     * <p>Held on the quest rather than on each objective's progress for one reason: two objectives
     * that ask for the same thing must get the same answer. "Place six lanterns at the dock" and
     * "place twelve chains at the dock" are one instruction about one dock, and per-objective storage
     * would let them bind to different docks the moment a second one qualified.
     */
    private final Map<String, FrozenLocation> frozenLocations = new HashMap<>();
    /**
     * This copy's own identity, distinct from {@code questId} and from the giver.
     *
     * <p>A player can hold, abandon and re-accept the same quest, and two copies of a repeatable quest
     * from two villagers are two obligations with two separate delivery ledgers. Nothing in the older
     * shape could tell them apart: {@code questId} plus giver UUID is the same pair for a re-accepted
     * copy, so a replayed delivery request could be applied to a quest the player has since restarted.
     *
     * <p>Minted lazily rather than at accept, and never in the constructor: a pre-1.6.5 save has no id,
     * and an id is only needed by something that is about to act on this copy. {@link #instance()} is
     * therefore the only way to get one, and the first caller that needs it persists it.
     */
    @Nullable
    private UUID instance;
    /** Opaque Ultima contract UUID; empty on every ordinary and legacy quest. */
    private boolean institutionalMarker;
    private String institutionalBinding = "";
    /** Canonical accepted definition, persisted so reloads cannot reinterpret institutional terms. */
    private String institutionalDefinitionJson = "";
    private String institutionalDefinitionFingerprint = "";
    private boolean rewardClaimed;
    private boolean readyNotified;
    /**
     * Game ticks this quest has spent unplayable because an optional mod one of its objectives reads
     * was absent (Townstead spec 10.1). Subtracted from "now" when a deadline is checked, so a quest
     * suspended for three in-game days is not instantly failed the moment the mod comes back.
     *
     * <p>Deliberately <em>not</em> folded into {@code startGameTime}: a failure spec may set
     * {@code deadline_time_of_day}, which anchors on the time of day the quest was accepted, and moving
     * the start would retarget that deadline to a different hour of the day instead of merely
     * postponing it. Offsetting the comparison freezes the clock, which is what suspension means, and
     * is correct for both deadline kinds.
     *
     * <p>Absent on saves written before 1.4.0, and on every quest that was never suspended.
     */
    private long suspendedTicks;

    /**
     * The portion of {@link #suspendedTicks} credited by the shared situation clock. Kept separately
     * so an earlier local objective pause cannot consume credit for a later Capitals outage.
     */
    private long situationSuspendedTicks;

    /**
     * Lifecycle phases already announced to Townstead, one bit per
     * {@code TownsteadLifecycle.Phase} (Townstead spec 7.1). Persisted so a reconnect or a restart
     * cannot make a villager react a second time to something that happened days ago. Absent on saves
     * written before 1.4.0 and on every quest that never reached a phase.
     */
    private final java.util.BitSet dispatchedPhases = new java.util.BitSet();

    /**
     * Quest ids already reported as having gained objectives since a player accepted them, so the
     * padding in {@link #reconcile(QuestDefinition)} logs once per id rather than once per tick.
     */
    private static final Set<ResourceLocation> RECONCILED_QUESTS = ConcurrentHashMap.newKeySet();

    /** Lazily-built concretized definition, derived from {@link #template}; not persisted. */
    @Nullable
    private transient QuestDefinition resolvedCache;
    /**
     * The {@link QuestRegistry#generation()} {@link #resolvedCache} was built against, so a
     * {@code /reload} reaches a template quest a player is already holding. Compared against the
     * generation rather than against {@code base}'s identity, because a situation offer builds a fresh
     * definition instance every time it is asked for and would never match.
     */
    private transient int resolvedGeneration = -1;

    public ActiveQuest(ResourceLocation questId, UUID villagerUuid, Component villagerName,
                       @Nullable ResourceLocation villagerProfession, ResourceLocation dimension,
                       long startGameTime, List<ObjectiveProgress> progress, @Nullable ResolvedTemplate template,
                       @Nullable UUID situationInstance) {
        this(questId, villagerUuid, villagerName, villagerProfession, dimension, startGameTime,
                OptionalLong.empty(), OptionalInt.empty(), progress, template, situationInstance);
    }

    /** The 1.5.1 shape, carrying the world-clock start time and the giver's frozen village. */
    public ActiveQuest(ResourceLocation questId, UUID villagerUuid, Component villagerName,
                       @Nullable ResourceLocation villagerProfession, ResourceLocation dimension,
                       long startGameTime, OptionalLong startDayTime, OptionalInt villageId,
                       List<ObjectiveProgress> progress, @Nullable ResolvedTemplate template,
                       @Nullable UUID situationInstance) {
        this.questId = questId;
        this.villagerUuid = villagerUuid;
        this.villagerName = villagerName;
        this.villagerProfession = villagerProfession;
        this.dimension = dimension;
        this.startGameTime = startGameTime;
        this.startDayTime = startDayTime;
        this.villageId = villageId;
        this.progress = progress;
        this.template = template;
        this.situationInstance = situationInstance;
    }

    /** Fresh acceptance with empty progress for each objective. */
    public static ActiveQuest create(ResourceLocation questId, UUID villagerUuid, Component villagerName,
                                     @Nullable ResourceLocation villagerProfession, ResourceLocation dimension,
                                     long startGameTime, int objectiveCount, @Nullable ResolvedTemplate template) {
        return create(questId, villagerUuid, villagerName, villagerProfession, dimension,
                startGameTime, objectiveCount, template, null);
    }

    /** Fresh acceptance linked to an open situation (0.8.0); {@code situationInstance} may be {@code null}. */
    public static ActiveQuest create(ResourceLocation questId, UUID villagerUuid, Component villagerName,
                                     @Nullable ResourceLocation villagerProfession, ResourceLocation dimension,
                                     long startGameTime, int objectiveCount, @Nullable ResolvedTemplate template,
                                     @Nullable UUID situationInstance) {
        return create(questId, villagerUuid, villagerName, villagerProfession, dimension, startGameTime,
                OptionalLong.empty(), OptionalInt.empty(), objectiveCount, template, situationInstance);
    }

    /** Fresh acceptance carrying the world-clock start time and the giver's village (1.5.1). */
    public static ActiveQuest create(ResourceLocation questId, UUID villagerUuid, Component villagerName,
                                     @Nullable ResourceLocation villagerProfession, ResourceLocation dimension,
                                     long startGameTime, OptionalLong startDayTime, OptionalInt villageId,
                                     int objectiveCount, @Nullable ResolvedTemplate template,
                                     @Nullable UUID situationInstance) {
        List<ObjectiveProgress> progress = new ArrayList<>();
        for (int i = 0; i < objectiveCount; i++) {
            progress.add(new ObjectiveProgress());
        }
        return new ActiveQuest(questId, villagerUuid, villagerName, villagerProfession, dimension,
                startGameTime, startDayTime, villageId, progress, template, situationInstance);
    }

    /**
     * The definition to use for this active quest: the concretized template (objectives/rewards filled
     * from the frozen {@link #template} values) when this came from a template, otherwise {@code base}
     * unchanged. Cached so the per-second progress tick does not re-parse JSON. Falls back to
     * {@code base} if the stored values can no longer be substituted (e.g. a datapack changed). The cache
     * follows the registry generation, so a {@code /reload} that reshapes the template is picked up
     * instead of being served from before the reload until the world restarts.
     *
     * <p>Also the point where a held quest is reconciled with a definition that has since gained
     * objectives -- see {@link #reconcile(QuestDefinition)}.
     */
    public QuestDefinition resolve(QuestDefinition base) {
        if (!institutionalBinding.isEmpty()) {
            if (resolvedCache == null) {
                resolvedCache = InstitutionalCommissionBridge.decodeSnapshot(
                        institutionalDefinitionJson, institutionalDefinitionFingerprint).orElse(base);
            }
            return resolvedCache;
        }
        reconcile(base);
        if (template == null || base.template().isEmpty()) {
            return base;
        }
        int generation = QuestRegistry.generation();
        if (resolvedCache == null || resolvedGeneration != generation) {
            resolvedCache = base.template()
                    .flatMap(spec -> spec.toConcrete(template))
                    .map(base::withConcrete)
                    .orElse(base);
            resolvedGeneration = generation;
        }
        return resolvedCache;
    }

    /**
     * The placeholder resolver for dialogue/title, carrying the reserved {@code {player}} token. Built
     * fresh each call because {@code playerName} is per-recipient; it only wraps the value map (cheap).
     */
    public PlaceholderResolver textResolver(@Nullable String playerName) {
        return template == null
                ? PlaceholderResolver.forPlayerName(playerName)
                : new PlaceholderResolver(template, playerName);
    }

    /** {@link #textResolver(String)} using {@code player}'s MCA name (username fallback) for {@code {player}}. */
    public PlaceholderResolver textResolver(ServerPlayer player) {
        return textResolver(McaCompat.getPlayerName(player));
    }

    public ResourceLocation questId() {
        return questId;
    }

    public UUID villagerUuid() {
        return villagerUuid;
    }

    public Component villagerName() {
        return villagerName;
    }

    @Nullable
    public ResourceLocation villagerProfession() {
        return villagerProfession;
    }

    public ResourceLocation dimension() {
        return dimension;
    }

    public long startGameTime() {
        return startGameTime;
    }

    /** The world clock this quest was accepted at, or empty on a quest accepted before 1.5.1. */
    public OptionalLong startDayTime() {
        return startDayTime;
    }

    /** The giver's village id, frozen at accept time; empty when none resolved (or pre-1.5.1). */
    public OptionalInt villageId() {
        return villageId;
    }

    public Optional<KingdomBindingSnapshot> kingdomBinding() {
        return Optional.ofNullable(kingdomBinding);
    }

    /** Acceptance is the only writer; an existing snapshot always wins on replay. */
    public void bindKingdom(KingdomBindingSnapshot binding) {
        if (kingdomBinding == null) kingdomBinding = java.util.Objects.requireNonNull(binding, "binding");
    }

    public Optional<CivicBuildingBinding> civicBuildingBinding() {
        return Optional.ofNullable(civicBuildingBinding);
    }

    /** Recovery may replace the stable identifier with the deterministic same-family rebind. */
    public void bindCivicBuilding(CivicBuildingBinding binding) {
        civicBuildingBinding = java.util.Objects.requireNonNull(binding, "binding");
    }

    /** The giver's community, for the reward paths that must work without the giver entity. */
    public Optional<QuestReputation.Community> community() {
        return villageId.isPresent()
                ? Optional.of(new QuestReputation.Community(dimension, villageId.getAsInt()))
                : Optional.empty();
    }

    /** The open situation this quest was accepted from (0.8.0), or empty for an ordinary quest. */
    public java.util.Optional<UUID> situationInstance() {
        return java.util.Optional.ofNullable(situationInstance);
    }

    /**
     * Progress for objective {@code index}, growing the list if the definition has gained objectives
     * since this quest was accepted. Progress is stored positionally and every reader indexes it by
     * the live definition, so a datapack (or an update) that appends an objective to a quest a player
     * is already holding would otherwise throw on the next tick. The list is never trimmed: a
     * definition that loses an objective may leave a stale trailing entry, which nothing reads.
     */
    /**
     * This copy's identity, minting and persisting one if this quest predates the field.
     *
     * <p>Lazy migration rather than a save upgrade pass: an old quest gets its id the first time
     * something needs to name this copy — which is the first delivery, the first menu card carrying a
     * delivery action, or nothing at all for a quest that never delivers anything.
     */
    public UUID instance() {
        if (instance == null) {
            instance = UUID.randomUUID();
        }
        return instance;
    }

    /** This copy's identity if it already has one, without minting. */
    public java.util.Optional<UUID> instanceIfPresent() {
        return java.util.Optional.ofNullable(instance);
    }

    /** True when {@code candidate} names this copy. A copy with no id yet matches nothing. */
    public boolean isInstance(@Nullable UUID candidate) {
        return candidate != null && candidate.equals(instance);
    }

    /**
     * Freezes owner and terms before Ultima's accepted callback. The returned instance is already the
     * identity the callback must durably bind, although this quest has not yet entered player state.
     */
    public Optional<UUID> bindInstitutional(String binding, QuestDefinition acceptedDefinition) {
        if (!institutionalBinding.isEmpty() || !InstitutionalCommissionBridge.validBinding(binding)) {
            return Optional.empty();
        }
        Optional<InstitutionalCommissionBridge.Snapshot> snapshot =
                InstitutionalCommissionBridge.snapshot(acceptedDefinition);
        if (snapshot.isEmpty()) return Optional.empty();
        institutionalBinding = binding;
        institutionalMarker = true;
        institutionalDefinitionJson = snapshot.get().json();
        institutionalDefinitionFingerprint = snapshot.get().fingerprint();
        resolvedCache = acceptedDefinition;
        return Optional.of(instance());
    }

    public String institutionalBinding() {
        return institutionalBinding;
    }

    public boolean isInstitutional() {
        return institutionalMarker;
    }

    /** True only while the currently loaded datapack definition is byte-for-byte equivalent in codec form. */
    public boolean institutionalDefinitionMatches(QuestDefinition current) {
        return !isInstitutional()
                || InstitutionalCommissionBridge.matches(current, institutionalDefinitionFingerprint);
    }

    /** Prevents either persisted kind of quest from being reinterpreted as the other after a reload. */
    public boolean institutionalShapeMatches(QuestDefinition current) {
        return current != null && current.institutionalCommission() == isInstitutional()
                && institutionalDefinitionMatches(current);
    }

    public ObjectiveProgress progress(int index) {
        while (progress.size() <= index) {
            progress.add(new ObjectiveProgress());
        }
        return progress.get(index);
    }

    /**
     * Pads this quest's progress list up to {@code base}'s objective count, logging once per quest id.
     * The player is told nothing: the new objective simply shows up at 0, which is what it is.
     */
    private void reconcile(QuestDefinition base) {
        int wanted = base.objectives().size();
        if (progress.size() >= wanted) {
            return;
        }
        if (RECONCILED_QUESTS.add(questId)) {
            McaQuests.LOGGER.info("[MCA: Quests] Quest '{}' has more objectives ({}) than when it was "
                    + "accepted ({}); the new ones start at 0.", questId, wanted, progress.size());
        }
        while (progress.size() < wanted) {
            progress.add(new ObjectiveProgress());
        }
    }

    /**
     * Rolls and stores {@code amount} for reward {@code index} if nothing is stored yet, and returns the
     * stored value either way. Idempotent by construction: the first writer wins, so a duplicated accept
     * packet or a re-entrant call can never replace an amount the player has already been shown.
     */
    public int freezeReward(int index, int amount) {
        return frozenRewards.computeIfAbsent(index, i -> amount);
    }

    /** The amount frozen for reward {@code index}, or empty if that reward has no frozen value. */
    public OptionalInt frozenReward(int index) {
        Integer value = frozenRewards.get(index);
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    public boolean rewardClaimed() {
        return rewardClaimed;
    }

    public void setRewardClaimed(boolean claimed) {
        this.rewardClaimed = claimed;
    }

    /** Whether the player has already been notified (toast) that this quest is ready to turn in. */
    /**
     * Records that a lifecycle phase has been announced, and reports whether this call is the one that
     * did it. The single guard behind "every phase fires at most once".
     */
    public boolean markPhaseDispatched(int phase) {
        if (dispatchedPhases.get(phase)) {
            return false;
        }
        dispatchedPhases.set(phase);
        return true;
    }

    /** Ticks spent suspended so far. See the field javadoc for why this is not folded into the start. */
    public long suspendedTicks() {
        return suspendedTicks;
    }

    /** Accrues suspended time. Called once per polling pass while an objective reports unavailable. */
    public void addSuspendedTicks(long delta) {
        if (delta > 0L) {
            this.suspendedTicks += delta;
        }
    }

    public long situationSuspendedTicks() {
        return situationSuspendedTicks;
    }

    /** Credits shared pause time once, recording both its source and the total deadline offset. */
    public void addSituationSuspendedTicks(long delta) {
        if (delta > 0L) {
            situationSuspendedTicks += delta;
            addSuspendedTicks(delta);
        }
    }

    /**
     * "Now", with suspended time removed — the value every deadline comparison must use so a quest is
     * never failed for time that passed while it could not be played.
     */
    public long effectiveNow(long gameTime) {
        return gameTime - suspendedTicks;
    }

    /** The destination already chosen for this anchor fingerprint, or null if none has been. */
    @Nullable
    public FrozenLocation frozenLocation(String fingerprint) {
        return frozenLocations.get(fingerprint);
    }

    /**
     * Records a chosen destination, the first time only. Later calls are ignored rather than
     * overwriting: a frozen anchor that could be re-frozen would move the map marker under a player
     * mid-journey, which is the whole thing freezing exists to prevent.
     */
    public void freezeLocation(String fingerprint, FrozenLocation location) {
        frozenLocations.putIfAbsent(fingerprint, location);
    }

    /**
     * Any frozen destination that named a registered building, for the context line. Returns the first
     * because a single quest that froze two different building families is a shape no bundled content
     * uses and one the card has no room to show twice.
     */
    @Nullable
    public FrozenLocation anyFrozenBuilding() {
        for (FrozenLocation location : frozenLocations.values()) {
            if (location.family().isPresent()) {
                return location;
            }
        }
        return null;
    }

    public boolean readyNotified() {
        return readyNotified;
    }

    public void setReadyNotified(boolean notified) {
        this.readyNotified = notified;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("quest", questId.toString());
        tag.putUUID("villager", villagerUuid);
        tag.putString("villager_name", Component.Serializer.toJson(villagerName));
        if (villagerProfession != null) {
            tag.putString("profession", villagerProfession.toString());
        }
        tag.putString("dimension", dimension.toString());
        tag.putLong("start", startGameTime);
        if (startDayTime.isPresent()) {
            tag.putLong("start_day", startDayTime.getAsLong());
        }
        if (villageId.isPresent()) {
            tag.putInt("village", villageId.getAsInt());
        }
        if (kingdomBinding != null) tag.put("kingdom_binding", kingdomBinding.save());
        if (civicBuildingBinding != null) tag.put("civic_building_binding", civicBuildingBinding.save());
        tag.putBoolean("claimed", rewardClaimed);
        tag.putBoolean("ready_notified", readyNotified);
        if (suspendedTicks != 0L) {
            tag.putLong("suspended_ticks", suspendedTicks);
        }
        if (situationSuspendedTicks != 0L) {
            tag.putLong("situation_suspended_ticks", situationSuspendedTicks);
        }
        if (!dispatchedPhases.isEmpty()) {
            tag.putByteArray("townstead_phases", dispatchedPhases.toByteArray());
        }
        ListTag list = new ListTag();
        for (ObjectiveProgress p : progress) {
            list.add(p.save());
        }
        tag.put("progress", list);
        if (template != null) {
            tag.put("template", template.save());
        }
        if (situationInstance != null) {
            tag.putUUID("situation", situationInstance);
        }
        if (instance != null) {
            // Absent on every quest that never needed an identity, so an untouched save stays untouched.
            tag.putUUID("instance", instance);
        }
        if (institutionalMarker) {
            tag.putString("institutional_binding", institutionalBinding);
            tag.putString("institutional_definition", institutionalDefinitionJson);
            tag.putString("institutional_definition_fingerprint", institutionalDefinitionFingerprint);
        }
        if (!frozenRewards.isEmpty()) {
            CompoundTag frozen = new CompoundTag();
            frozenRewards.forEach((index, amount) -> frozen.putInt(String.valueOf(index), amount));
            tag.put("frozen_rewards", frozen);
        }
        if (!frozenLocations.isEmpty()) {
            CompoundTag locations = new CompoundTag();
            frozenLocations.forEach((fingerprint, location) -> locations.put(fingerprint, location.save()));
            tag.put("frozen_locations", locations);
        }
        return tag;
    }

    public static ActiveQuest load(CompoundTag tag) {
        Component name;
        try {
            name = Component.Serializer.fromJson(tag.getString("villager_name"));
        } catch (RuntimeException malformedName) {
            name = Component.literal(tag.getString("villager_name"));
        }
        ResourceLocation profession = tag.contains("profession")
                ? ResourceLocation.tryParse(tag.getString("profession")) : null;
        List<ObjectiveProgress> progress = new ArrayList<>();
        ListTag list = tag.getList("progress", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            progress.add(ObjectiveProgress.load(list.getCompound(i)));
        }
        ResolvedTemplate template = tag.contains("template")
                ? ResolvedTemplate.load(tag.getCompound("template")) : null;
        UUID situationInstance = tag.hasUUID("situation") ? tag.getUUID("situation") : null;
        ActiveQuest quest = new ActiveQuest(
                new ResourceLocation(tag.getString("quest")),
                tag.getUUID("villager"),
                name != null ? name : Component.empty(),
                profession,
                new ResourceLocation(tag.getString("dimension")),
                tag.getLong("start"),
                tag.contains("start_day") ? OptionalLong.of(tag.getLong("start_day")) : OptionalLong.empty(),
                tag.contains("village") ? OptionalInt.of(tag.getInt("village")) : OptionalInt.empty(),
                progress,
                template,
                situationInstance);
        if (tag.hasUUID("instance")) {
            quest.instance = tag.getUUID("instance");
        }
        // Raw key presence is the trust boundary. A corrupt wrong-type value must stay institutional
        // and fail its bounded typed reads below, never silently downgrade into an ordinary quest.
        boolean institutionalMarker = tag.contains("institutional_binding")
                || tag.contains("institutional_definition")
                || tag.contains("institutional_definition_fingerprint");
        if (institutionalMarker) {
            quest.institutionalMarker = true;
            String institutionalBinding = tag.getString("institutional_binding");
            String institutionalDefinition = tag.getString("institutional_definition");
            String institutionalFingerprint = tag.getString("institutional_definition_fingerprint");
            // Presence is the security boundary. Bound malformed content before retaining it so corrupted
            // data fails closed without carrying an unbounded string through every later save.
            quest.institutionalBinding = institutionalBinding.length() <= 128 ? institutionalBinding : "";
            quest.institutionalDefinitionJson = institutionalDefinition.length() <= 131_072
                    ? institutionalDefinition : "";
            quest.institutionalDefinitionFingerprint = institutionalFingerprint.length() <= 128
                    ? institutionalFingerprint : "";
        }
        if (tag.contains("kingdom_binding", Tag.TAG_COMPOUND)) {
            KingdomBindingSnapshot.load(tag.getCompound("kingdom_binding")).ifPresent(quest::bindKingdom);
        }
        if (tag.contains("civic_building_binding", Tag.TAG_COMPOUND)) {
            CivicBuildingBinding.load(tag.getCompound("civic_building_binding"))
                    .ifPresent(quest::bindCivicBuilding);
        }
        quest.rewardClaimed = tag.getBoolean("claimed");
        quest.readyNotified = tag.getBoolean("ready_notified");
        quest.suspendedTicks = Math.max(0L, tag.getLong("suspended_ticks")); // 0 when absent
        quest.situationSuspendedTicks = Math.max(0L,
                Math.min(quest.suspendedTicks, tag.getLong("situation_suspended_ticks")));
        if (tag.contains("townstead_phases", Tag.TAG_BYTE_ARRAY)) {
            quest.dispatchedPhases.or(java.util.BitSet.valueOf(tag.getByteArray("townstead_phases")));
        }
        if (tag.contains("frozen_locations", Tag.TAG_COMPOUND)) {
            CompoundTag locations = tag.getCompound("frozen_locations");
            for (String fingerprint : locations.getAllKeys()) {
                FrozenLocation location = FrozenLocation.load(locations.getCompound(fingerprint));
                if (location != null) {
                    quest.frozenLocations.put(fingerprint, location);
                }
            }
        }
        if (tag.contains("frozen_rewards", Tag.TAG_COMPOUND)) {
            CompoundTag frozen = tag.getCompound("frozen_rewards");
            for (String key : frozen.getAllKeys()) {
                try {
                    quest.frozenRewards.put(Integer.parseInt(key), frozen.getInt(key));
                } catch (NumberFormatException ignored) {
                    // Drop an unparseable index rather than failing the whole quest load.
                }
            }
        }
        return quest;
    }
}
