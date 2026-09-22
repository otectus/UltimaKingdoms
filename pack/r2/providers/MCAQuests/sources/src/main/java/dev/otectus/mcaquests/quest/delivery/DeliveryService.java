package dev.otectus.mcaquests.quest.delivery;

import dev.otectus.mcaquests.McaQuests;
import dev.otectus.mcaquests.McaQuestsConfig;
import dev.otectus.mcaquests.compat.mca.McaBinding;
import dev.otectus.mcaquests.compat.mca.McaGiftHookProbe;
import dev.otectus.mcaquests.quest.CapitalsQuestRequirements;
import dev.otectus.mcaquests.quest.situation.QuestDefinitions;
import dev.otectus.mcaquests.quest.QuestDefinition;
import dev.otectus.mcaquests.quest.InstitutionalCommissionBridge;
import dev.otectus.mcaquests.quest.QuestManager;
import dev.otectus.mcaquests.quest.objective.DeliverToVillagerObjective;
import dev.otectus.mcaquests.quest.objective.DeliveryDestination;
import dev.otectus.mcaquests.quest.objective.InventoryTransfer;
import dev.otectus.mcaquests.quest.objective.ItemDeliveryObjective;
import dev.otectus.mcaquests.quest.objective.ObjectiveProgress;
import dev.otectus.mcaquests.quest.objective.ObjectiveSupport;
import dev.otectus.mcaquests.quest.objective.QuestObjective;
import dev.otectus.mcaquests.state.ActiveQuest;
import dev.otectus.mcaquests.state.PlayerQuestData;
import dev.otectus.mcaquests.state.QuestCapabilities;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The one place goods move from a player to a quest.
 *
 * <p>Every route in — the Deliver action on a card, MCA's own Gift gesture, the legacy right-click
 * hand-off and final turn-in — comes through {@link #commit}. That is the whole point of this class:
 * before it existed each entry point took items its own way, so "how many has the player actually
 * given?" had as many answers as there were callers, and only one of them wrote anything down.
 *
 * <p>The order inside a commit is fixed and matters:
 *
 * <ol>
 *   <li>validate everything (caller, quest copy, objective, recipient, reach, bounds) and mutate nothing;</li>
 *   <li>plan the whole batch, and abandon it if any part of it cannot succeed;</li>
 *   <li>commit the inventory transaction once;</li>
 *   <li><em>then</em> write the ledger, and only for units that actually moved;</li>
 *   <li>settle quest state and refresh what the player is looking at.</li>
 * </ol>
 *
 * <p>A failure before step 3 takes nothing and records nothing. A committed deposit is never rolled
 * back afterwards, including when a later reward policy refuses to finalize the quest: the goods are
 * gone, and pretending otherwise would charge the player twice on the retry.
 */
public final class DeliveryService {

    private DeliveryService() {
    }

    /**
     * True while a commit is running on the server thread.
     *
     * <p>Committing an inventory can run a container listener, and MCA's villager inventory does. That
     * is third-party code which may reach quest state and, through an add-on, ask for another delivery.
     * A nested request would plan against inventories that are half-written, so it is refused as stale
     * rather than served.
     */
    private static boolean inFlight;

    /**
     * Request ids already served, newest last.
     *
     * <p>A menu click carries its own id, and a duplicated packet — a double click, a resend, a
     * replayed one — carries the same one. Claiming it here is what makes "process each actual
     * invocation once" true for the networked route: the second copy is refused as stale instead of
     * charging the player again for goods they authorized once.
     *
     * <p>Bounded and forgetful on purpose. Ids are random UUIDs, so a fixed ring is enough to cover
     * any plausible burst, and forgetting an old one cannot cause a double charge — the revision check
     * and the ledger still stand behind it.
     */
    private static final Set<UUID> SERVED_REQUESTS = Collections.newSetFromMap(
            new LinkedHashMap<UUID, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > 256;
                }
            });

    /**
     * Claims {@code requestId}, or reports that it has already been served.
     *
     * <p>{@code null} is always claimable: it means a route the server drove itself, which has no
     * client packet to be replayed.
     */
    public static synchronized boolean claimRequest(@Nullable UUID requestId) {
        return requestId == null || SERVED_REQUESTS.add(requestId);
    }

    /** Test seam: forget every served request. Production never resets. */
    public static synchronized void resetRequestsForTest() {
        SERVED_REQUESTS.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // The obligation: the delivery-relevant shape of an objective, so nothing else branches on type
    // ---------------------------------------------------------------------------------------------

    /**
     * One item obligation, flattened out of whichever of the two delivery objective types authored it.
     *
     * @param proofOnly {@code consume: false} with no transfer destination — goods are shown, not given
     * @param transfers the goods go into a container rather than being destroyed
     */
    public record Obligation(int index, QuestObjective objective, int required, boolean proofOnly,
                             boolean transfers, Predicate<ItemStack> matcher, Component itemName,
                             String fingerprint, @Nullable DeliveryDestination destination) {

        /** Empty for every objective that is not an item delivery. */
        public static Optional<Obligation> of(QuestObjective objective, int index) {
            if (objective instanceof DeliverToVillagerObjective deliver) {
                DeliveryDestination destination = deliver.destination().orElse(null);
                return Optional.of(new Obligation(index, objective, deliver.itemCount(),
                        DeliveryLedger.isProofOnly(objective),
                        destination != null && destination.isTransfer(),
                        deliver.item()::matches, deliver.item().describe(),
                        DeliveryLedger.fingerprintOf(objective), destination));
            }
            if (objective instanceof ItemDeliveryObjective delivery) {
                return Optional.of(new Obligation(index, objective, delivery.count(),
                        DeliveryLedger.isProofOnly(objective), delivery.destination().isTransfer(),
                        stack -> stack.is(delivery.item()), delivery.item().getDescription(),
                        DeliveryLedger.fingerprintOf(objective), delivery.destination()));
            }
            return Optional.empty();
        }
    }

    /** The structured answer to a request: what happened, and how far the obligation has got. */
    public record Outcome(DeliveryResult result, int units, int delivered, int required) {

        public static Outcome of(DeliveryResult result) {
            return new Outcome(result, 0, 0, 0);
        }

        public boolean isSuccess() {
            return result.isSuccess();
        }

        /** True when items left the player for this outcome — what the MCA Gift bridge suppresses on. */
        public boolean consumedUnits() {
            return result.consumedUnits() && units > 0;
        }

        /** The sentence for the player. The two counting results carry their numbers; the rest do not. */
        public Component message() {
            return result.consumedUnits() ? result.message(delivered, required) : result.message();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------------

    /**
     * Every delivery obligation of this player's active quests that {@code villager} has something to do
     * with, whether or not that villager gave the quest.
     *
     * <p>A recipient who was never the giver and has no offers of their own still has to be able to
     * accept what was sent to them; leaving them out of this list is what made a "take these to Rowan"
     * quest impossible to finish through the interface.
     */
    public static List<DeliveryView> viewsFor(ServerPlayer player, Entity villager) {
        if (!(player.level() instanceof ServerLevel level) || !(villager instanceof LivingEntity recipient)) {
            return List.of();
        }
        PlayerQuestData data = QuestCapabilities.get(player).orElse(null);
        if (data == null) {
            return List.of();
        }
        List<DeliveryView> views = new ArrayList<>();
        for (ActiveQuest active : new ArrayList<>(data.active())) {
            QuestDefinition base = QuestDefinitions.resolve(active.questId()).orElse(null);
            if (base == null || active.rewardClaimed()) {
                continue;
            }
            QuestDefinition def = active.resolve(base);
            List<QuestObjective> objectives = def.objectives();
            for (int i = 0; i < objectives.size(); i++) {
                Obligation obligation = Obligation.of(objectives.get(i), i).orElse(null);
                if (obligation == null) {
                    continue;
                }
                views.add(view(player, level, active, def, obligation, recipient));
            }
        }
        return views;
    }

    /**
     * The active quest copies with an unsatisfied delivery {@code villager} is authorized to accept.
     *
     * <p>What the quest menu needs in order to show an incoming parcel to a villager who is neither the
     * giver nor the reward's claimant. Returned as the quest copies themselves, by identity, so two
     * copies of the same repeatable quest are not confused for one another.
     */
    public static List<ActiveQuest> recipientActives(ServerPlayer player, Entity villager) {
        if (!(player.level() instanceof ServerLevel level) || !(villager instanceof LivingEntity recipient)) {
            return List.of();
        }
        PlayerQuestData data = QuestCapabilities.get(player).orElse(null);
        if (data == null) {
            return List.of();
        }
        List<ActiveQuest> awaiting = new ArrayList<>();
        for (ActiveQuest active : new ArrayList<>(data.active())) {
            QuestDefinition base = QuestDefinitions.resolve(active.questId()).orElse(null);
            if (base == null || active.rewardClaimed()) {
                continue;
            }
            QuestDefinition def = active.resolve(base);
            List<QuestObjective> objectives = def.objectives();
            for (int i = 0; i < objectives.size(); i++) {
                Obligation obligation = Obligation.of(objectives.get(i), i).orElse(null);
                if (obligation == null) {
                    continue;
                }
                DeliveryView view = view(player, level, active, def, obligation, recipient);
                if (actionable(view)) {
                    awaiting.add(active);
                    break;
                }
            }
        }
        return awaiting;
    }

    /** The unsatisfied obligations {@code villager} may be handed right now. */
    public static List<DeliveryView> deliverableAt(ServerPlayer player, Entity villager) {
        return viewsFor(player, villager).stream()
                .filter(view -> view.deliverableHere() && !view.satisfied())
                .toList();
    }

    /** One obligation as the client needs to see it, including why its action is unavailable. */
    public static DeliveryView view(ServerPlayer player, ServerLevel level, ActiveQuest active,
                                    QuestDefinition def, Obligation obligation, @Nullable LivingEntity villager) {
        ObjectiveProgress progress = active.progress(obligation.index());
        // A villager proof objective has no units but does have a sticky "they have seen these"; showing
        // it as delivered is how the card says so without claiming anything was taken.
        int delivered = obligation.proofOnly()
                ? (proofSatisfied(obligation, progress) ? obligation.required() : 0)
                : DeliveryLedger.units(obligation.objective(), progress);
        IntSet slots = InventoryTransfer.defaultSourceSlots(player.getInventory());
        int available = obligation.proofOnly()
                // Proof has always meant "are you carrying these?", every slot included; it takes nothing.
                ? InventoryTransfer.countIn(player.getInventory(), null, obligation.matcher())
                : InventoryTransfer.countIn(player.getInventory(), slots, obligation.matcher());
        Optional<LivingEntity> resolved = DeliveryRecipientResolver.resolve(obligation.objective(), player,
                active, progress, level);
        DeliveryResult reason = refusal(player, level, active, def, obligation, progress, villager, delivered,
                available);
        boolean authorized = villager != null && reason == null;
        // Minting the copy's id here is the lazy migration: a view is the first thing that needs to name
        // this copy rather than the quest, and naming it is what stops a request being applied to a
        // re-accepted copy of the same quest.
        return new DeliveryView(active.questId(), active.instance(), obligation.index(),
                obligation.itemName(), delivered, obligation.required(), available,
                DeliveryRecipientResolver.describe(obligation.objective(), player, active, progress, level),
                resolved.map(LivingEntity::getUUID).orElse(null), authorized,
                authorized && giftBridgeAvailable(), obligation.proofOnly(),
                reason);
    }

    /** Why this villager cannot be handed these goods right now, or {@code null} when they can. */
    @Nullable
    private static DeliveryResult refusal(ServerPlayer player, ServerLevel level, ActiveQuest active,
                                          QuestDefinition def, Obligation obligation, ObjectiveProgress progress,
                                          @Nullable LivingEntity villager, int delivered, int available) {
        if (paused(player, level, active, def, obligation, progress)) {
            return DeliveryResult.OBJECTIVE_PAUSED;
        }
        if (DeliveryLedger.blocked(progress, obligation.fingerprint())) {
            return DeliveryResult.STALE_REQUEST;
        }
        if (villager == null || !DeliveryRecipientResolver.reachable(player, villager)) {
            return DeliveryResult.RECIPIENT_UNAVAILABLE;
        }
        if (!DeliveryRecipientResolver.authorizes(obligation.objective(), player, active, def, progress,
                villager, level)) {
            return DeliveryResult.WRONG_RECIPIENT;
        }
        if (obligation.proofOnly()) {
            if (delivered >= obligation.required()) {
                return DeliveryResult.ALREADY_DELIVERED; // a villager proof stays shown once shown
            }
            return available >= obligation.required() ? null : DeliveryResult.NO_MATCHING_ITEMS;
        }
        if (delivered >= obligation.required()) {
            return DeliveryResult.ALREADY_DELIVERED;
        }
        return available <= 0 ? DeliveryResult.NO_MATCHING_ITEMS : null;
    }

    /**
     * Whether this obligation is currently unreadable, which is not the same question as whether the
     * quest has some other paused objective: a suspended Townstead reading elsewhere must not stop an
     * ordinary "bring me six loaves" from being paid.
     */
    private static boolean paused(ServerPlayer player, ServerLevel level, ActiveQuest active,
                                  QuestDefinition def, Obligation obligation, ObjectiveProgress progress) {
        return CapitalsQuestRequirements.unavailableReason(def).isPresent()
                || obligation.objective().unavailableReason(player, active, progress, level).isPresent();
    }

    // ---------------------------------------------------------------------------------------------
    // The MCA Gift bridge's availability, which is a different question from whether MCA is installed
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether MCA's own Gift gesture can currently pay a delivery.
     *
     * <p>Two things have to be true: the owner has not switched the route off, and the hook is
     * actually in MCA's command handler. The second is not implied by the first — MCA may be a build
     * whose shape the mixin plugin refuses — and a card that advertised Gift on such an installation
     * would send the player to do something that silently gives their quest item away as an ordinary
     * present.
     */
    public static boolean giftBridgeAvailable() {
        // Also the moment the hook's once-per-session line is worth printing: a mixin applies when its
        // target class loads, which is when somebody talks to a villager, so common setup is too early
        // to know. This is the first place the answer decides anything.
        McaBinding.logGiftHookOnce();
        return McaQuestsConfig.COMMON.enableMcaGiftDelivery.get() && McaGiftHookProbe.applied();
    }

    /**
     * Why Gift cannot pay a delivery on this installation, or empty when it can.
     *
     * <p>{@link DeliveryResult#BRIDGE_UNAVAILABLE} is reserved for the one case worth reporting: MCA
     * is here, the owner wants the route, and the hook did not go in. An absent MCA needs no
     * explanation, and a route the owner turned off is not a fault.
     */
    public static Optional<DeliveryResult> giftBridgeRefusal() {
        if (!McaQuestsConfig.COMMON.enableMcaGiftDelivery.get() || McaGiftHookProbe.applied()) {
            return Optional.empty();
        }
        return McaGiftHookProbe.mcaPresent()
                ? Optional.of(DeliveryResult.BRIDGE_UNAVAILABLE) : Optional.empty();
    }

    /**
     * Whether this villager has anything to do with this obligation — quest intent exists here,
     * blocked or not.
     *
     * <p>Deliberately keeps the blocked ones. Discarding every unpayable match first and only then
     * asking whether a quest wanted the item is what turns "their inventory is full" into an ordinary
     * gift that takes the requested item and credits nothing; here it becomes a refusal the player can
     * act on, and a card they can see. Only the two answers that mean "this is not this villager's
     * business at all" drop out.
     */
    public static boolean actionable(DeliveryView view) {
        if (view.satisfied()) {
            return false;
        }
        return view.reasonIfAny()
                .map(reason -> reason != DeliveryResult.WRONG_RECIPIENT
                        && reason != DeliveryResult.RECIPIENT_UNAVAILABLE)
                .orElse(true);
    }

    /**
     * The one obligation a Gift means, or empty when the gesture is genuinely ambiguous.
     *
     * <p>Pure, so the rule can be asserted rather than described: one match wins outright; several
     * matches are decided only by the quest the player is following, and only when that quest has a
     * single matching obligation of its own. Anything else is a question for the menu's chooser — the
     * item is not taken to make a guess.
     */
    public static Optional<DeliveryView> chooseFrom(List<DeliveryView> matching,
                                                    @Nullable ResourceLocation trackedQuest,
                                                    @Nullable UUID trackedInstance) {
        if (matching.size() == 1) {
            return Optional.of(matching.get(0));
        }
        if (matching.isEmpty() || trackedQuest == null) {
            return Optional.empty();
        }
        List<DeliveryView> onTracked = matching.stream()
                .filter(view -> view.questId().equals(trackedQuest))
                .filter(view -> trackedInstance == null || trackedInstance.equals(view.instance()))
                .toList();
        return onTracked.size() == 1 ? Optional.of(onTracked.get(0)) : Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------
    // Commit
    // ---------------------------------------------------------------------------------------------

    /**
     * Hands over up to what {@code request} authorized, and records exactly what moved.
     *
     * <p>Server thread only, and single-entrant. Everything the client supplied is treated as a claim
     * about intent, never as progress: the units credited are the units this method removed itself.
     */
    public static Outcome commit(ServerPlayer player, DeliveryRequest request) {
        if (player.getServer() == null || !player.getServer().isSameThread()
                || !(player.level() instanceof ServerLevel level)) {
            return Outcome.of(DeliveryResult.INVALID_REQUEST);
        }
        if (inFlight || InventoryTransfer.isCommitting()) {
            McaQuests.LOGGER.warn("[MCA: Quests] refused a delivery that reentered from inside another one"
                    + " (quest {}, objective {})", request.questId(), request.objectiveIndex());
            return Outcome.of(DeliveryResult.STALE_REQUEST);
        }
        if (!claimRequest(request.requestId())) {
            // The same click, twice. Refusing it here is cheaper and safer than letting the revision
            // check catch it, and it is the only guard that works when the first copy is still in
            // flight on the queue.
            return Outcome.of(DeliveryResult.STALE_REQUEST);
        }
        inFlight = true;
        try {
            return commitOnce(player, level, request);
        } finally {
            inFlight = false;
        }
    }

    private static Outcome commitOnce(ServerPlayer player, ServerLevel level, DeliveryRequest request) {
        PlayerQuestData data = QuestCapabilities.get(player).orElse(null);
        if (data == null) {
            return Outcome.of(DeliveryResult.INVALID_REQUEST);
        }
        ActiveQuest active = find(data, request);
        if (active == null) {
            return Outcome.of(DeliveryResult.STALE_REQUEST);
        }
        QuestDefinition base = QuestDefinitions.resolve(active.questId()).orElse(null);
        if (base == null) {
            return Outcome.of(DeliveryResult.STALE_REQUEST);
        }
        QuestDefinition def = active.resolve(base);
        if (request.objectiveIndex() >= def.objectives().size()) {
            return Outcome.of(DeliveryResult.INVALID_REQUEST);
        }
        Obligation obligation = Obligation.of(def.objectives().get(request.objectiveIndex()),
                request.objectiveIndex()).orElse(null);
        if (obligation == null) {
            return Outcome.of(DeliveryResult.INVALID_REQUEST);
        }
        Entity candidate = level.getEntity(request.recipientUuid());
        if (!(candidate instanceof LivingEntity recipient) || !DeliveryRecipientResolver.reachable(player, recipient)) {
            return Outcome.of(DeliveryResult.RECIPIENT_UNAVAILABLE);
        }
        return commitTo(player, level, active, def, obligation, recipient, request);
    }

    /**
     * The transaction itself, with the request already resolved to a live quest, objective and recipient.
     *
     * <p>Split out so the legacy hand-off and the add-on entry points reach the same body without
     * rebuilding a request they already hold the answers to.
     */
    private static Outcome commitTo(ServerPlayer player, ServerLevel level, ActiveQuest active,
                                    QuestDefinition def, Obligation obligation, LivingEntity recipient,
                                    DeliveryRequest request) {
        QuestDefinition current = QuestDefinitions.resolve(active.questId()).orElse(null);
        if (current == null || !active.institutionalShapeMatches(current)) {
            return Outcome.of(DeliveryResult.OBJECTIVE_PAUSED);
        }
        if (active.isInstitutional()) {
            Entity issuer = level.getEntity(active.villagerUuid());
            if (issuer == null) {
                return Outcome.of(DeliveryResult.OBJECTIVE_PAUSED);
            }
            String refusal = InstitutionalCommissionBridge.validate(player, issuer, active.questId(),
                    active.institutionalBinding(), true);
            if (!refusal.isEmpty()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(refusal));
                return Outcome.of(DeliveryResult.OBJECTIVE_PAUSED);
            }
        }
        ObjectiveProgress progress = active.progress(obligation.index());
        if (paused(player, level, active, def, obligation, progress)) {
            return Outcome.of(DeliveryResult.OBJECTIVE_PAUSED);
        }
        if (DeliveryLedger.blocked(progress, obligation.fingerprint())) {
            return Outcome.of(DeliveryResult.STALE_REQUEST);
        }
        boolean authorized = request.method() == DeliveryRequest.Method.GIFT
                ? DeliveryRecipientResolver.giftRoutable(obligation.objective(), player, active, def, progress,
                        recipient, level)
                : DeliveryRecipientResolver.authorizes(obligation.objective(), player, active, def, progress,
                        recipient, level);
        if (!authorized) {
            return Outcome.of(DeliveryResult.WRONG_RECIPIENT);
        }
        if (obligation.proofOnly()) {
            if (proofSatisfied(obligation, progress)) {
                return new Outcome(DeliveryResult.ALREADY_DELIVERED, 0, obligation.required(),
                        obligation.required());
            }
            if (request.hasRevision() && request.expectedRevision() != 0) {
                return new Outcome(DeliveryResult.STALE_REQUEST, 0, 0, obligation.required());
            }
            return acknowledgeProof(player, obligation, progress, recipient, request);
        }
        int delivered = DeliveryLedger.units(obligation.objective(), progress);
        if (request.hasRevision() && request.expectedRevision() != delivered) {
            // The card this click was made against is out of date: somebody already paid part of this
            // obligation. Refusing rather than silently re-deriving the amount is the point — the
            // player authorized a quantity for a state that no longer holds.
            return new Outcome(DeliveryResult.STALE_REQUEST, 0, delivered, obligation.required());
        }
        int outstanding = Math.max(0, obligation.required() - delivered);
        if (outstanding == 0) {
            return new Outcome(DeliveryResult.ALREADY_DELIVERED, 0, delivered, obligation.required());
        }
        Container destination = null;
        if (obligation.transfers()) {
            destination = resolveDestination(player, level, active, obligation, recipient).orElse(null);
            if (destination == null) {
                // Nobody to put them in, which is a different problem from somebody with no room.
                return new Outcome(DeliveryResult.RECIPIENT_UNAVAILABLE, 0, delivered, obligation.required());
            }
        }
        IntSet slots = authorizedSlots(player.getInventory(), request);
        int available = InventoryTransfer.countIn(player.getInventory(), slots, obligation.matcher());
        int deposit = Math.min(request.authorizedUnits(outstanding), available);
        if (deposit <= 0) {
            return new Outcome(DeliveryResult.NO_MATCHING_ITEMS, 0, delivered, obligation.required());
        }
        InventoryTransfer.Plan plan = plan(player, obligation.matcher(), slots, deposit, destination);
        if (plan == null) {
            // The only way a reservation of stock we just counted can fail is destination capacity.
            return new Outcome(destination != null ? DeliveryResult.DESTINATION_FULL
                    : DeliveryResult.NO_MATCHING_ITEMS, 0, delivered, obligation.required());
        }
        if (!plan.commit()) {
            return new Outcome(DeliveryResult.STALE_REQUEST, 0, delivered, obligation.required());
        }
        // Committed: from here the goods are gone, so the ledger is written before anything downstream
        // can throw, be cancelled, or decide the quest cannot be finalized after all.
        int total = DeliveryLedger.credit(progress, obligation.fingerprint(), deposit, obligation.required());
        if (total >= obligation.required()) {
            DeliveryLedger.mirrorLegacyMarkers(obligation.objective(), progress);
        }
        settle(player, recipient, request);
        DeliveryResult result = total >= obligation.required()
                ? DeliveryResult.DELIVERY_SATISFIED : DeliveryResult.DELIVERED_PARTIAL;
        return new Outcome(result, deposit, total, obligation.required());
    }

    /**
     * "Show me the items": the full quantity has to be in hand at once, and nothing is taken.
     *
     * <p>Kept exactly as it was rather than folded into the deposit formula, because a player who shows
     * the same crossbow six times has donated nothing — banking six units for one item is precisely the
     * cheat the deposit ledger would otherwise open here.
     */
    private static Outcome acknowledgeProof(ServerPlayer player, Obligation obligation,
                                            ObjectiveProgress progress, LivingEntity recipient,
                                            DeliveryRequest request) {
        int carried = InventoryTransfer.countIn(player.getInventory(), null, obligation.matcher());
        if (carried < obligation.required()) {
            return new Outcome(DeliveryResult.NO_MATCHING_ITEMS, 0, 0, obligation.required());
        }
        DeliveryLedger.acknowledgeProof(progress, obligation.fingerprint());
        DeliveryLedger.mirrorLegacyMarkers(obligation.objective(), progress);
        settle(player, recipient, request);
        return new Outcome(DeliveryResult.PROOF_ACKNOWLEDGED, 0, 0, obligation.required());
    }

    /**
     * Whether a proof objective has already been answered.
     *
     * <p>The two kinds mean different things and both are preserved. A villager proof is <b>sticky</b>:
     * once shown, it stays shown, because the acknowledgement happened in front of the villager. An
     * inventory proof is <b>live</b>: it asks whether the player is carrying all of them right now, and
     * dropping them un-answers it, which is what it has always done.
     */
    private static boolean proofSatisfied(Obligation obligation, ObjectiveProgress progress) {
        return obligation.objective() instanceof DeliverToVillagerObjective
                && DeliveryLedger.migrateProof(progress, obligation.fingerprint(),
                        DeliveryLedger.legacySatisfied(obligation.objective(), progress));
    }

    /**
     * Reserves the deposit, preferring stacks a player would not mind parting with.
     *
     * <p>Two passes: ordinary stock first, then everything the allowlist permits. Damage is not what
     * makes a stack precious — the two crossbows this whole feature exists for are damaged — so only a
     * custom name or an enchantment holds a stack back, and even then only while plain stock can cover
     * the batch on its own.
     */
    @Nullable
    private static InventoryTransfer.Plan plan(ServerPlayer player, Predicate<ItemStack> matcher, IntSet slots,
                                               int deposit, @Nullable Container destination) {
        IntSet plainSlots = plainSlots(player.getInventory(), slots, matcher);
        if (InventoryTransfer.countIn(player.getInventory(), plainSlots, matcher) >= deposit) {
            InventoryTransfer.Plan preferred = new InventoryTransfer.Plan(player.getInventory());
            if (preferred.reserve(plainSlots, matcher, deposit, destination)) {
                return preferred;
            }
        }
        InventoryTransfer.Plan any = new InventoryTransfer.Plan(player.getInventory());
        return any.reserve(slots, matcher, deposit, destination) ? any : null;
    }

    private static IntSet plainSlots(Inventory inventory, IntSet slots, Predicate<ItemStack> matcher) {
        IntSet plain = new IntOpenHashSet();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!slots.contains(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && matcher.test(stack) && InventoryTransfer.isPlain(stack)) {
                plain.add(slot);
            }
        }
        return plain;
    }

    /**
     * The slots this request may debit.
     *
     * <p>An unqualified request gets the hotbar and the main inventory. A request that names its slots
     * is additionally allowed the offhand — a player can choose to hand over what they are holding
     * there — but never worn armour, and never a slot outside the allowlist it asked for.
     */
    private static IntSet authorizedSlots(Inventory inventory, DeliveryRequest request) {
        if (!request.hasExplicitSlots()) {
            return InventoryTransfer.defaultSourceSlots(inventory);
        }
        IntSet permitted = InventoryTransfer.sourceSlots(inventory, true, false);
        IntSet selected = new IntOpenHashSet();
        request.slots().forEach((int slot) -> {
            if (permitted.contains(slot)) {
                selected.add(slot);
            }
        });
        return selected;
    }

    /**
     * The container an authored destination means, or {@code null} when it cannot take the goods.
     *
     * <p>A {@code deliver_to_villager} destination is about the recipient standing in front of the
     * player, which is why the already-matched recipient is passed rather than re-resolved — nothing a
     * client sends can redirect the goods. An {@code item_delivery} destination is about the giver, so
     * the giver is preferred while they are loaded and the recipient stands in when they are not, which
     * is the same substitution the turn-in path has always made.
     */
    private static Optional<Container> resolveDestination(ServerPlayer player, ServerLevel level,
                                                          ActiveQuest active, Obligation obligation,
                                                          LivingEntity recipient) {
        DeliveryDestination destination = obligation.destination();
        if (destination == null || !destination.isTransfer()) {
            return Optional.empty();
        }
        Entity context = recipient;
        if (obligation.objective() instanceof ItemDeliveryObjective
                && !recipient.getUUID().equals(active.villagerUuid())) {
            context = ObjectiveSupport.giver(level, active).orElse(recipient);
        }
        return destination.resolveContainer(player, context);
    }

    /**
     * Pushes the committed deposit into everything that shows it, without waiting for the next poll.
     *
     * <p>The menu is re-sent only for a menu request. A Gift or a right-click happens with MCA's own
     * screen open, and pushing a quest menu at that moment would replace the screen the player is
     * actually using.
     */
    private static void settle(ServerPlayer player, @Nullable LivingEntity recipient, DeliveryRequest request) {
        QuestManager.settleProgress(player);
        // A request with an id came over the wire, and the handler that decoded it re-sends the menu
        // itself — with the result line on it, which only it knows. Re-sending here as well would
        // replace that screen with a silent copy of the same cards.
        if (request.method() == DeliveryRequest.Method.MENU && recipient != null
                && request.requestId() == null) {
            QuestManager.sendMenu(player, recipient);
        }
    }

    @Nullable
    private static ActiveQuest find(PlayerQuestData data, DeliveryRequest request) {
        ResourceLocation questId = request.questId();
        UUID instance = request.instance();
        ActiveQuest fallback = null;
        for (ActiveQuest active : data.active()) {
            if (!active.questId().equals(questId) || active.rewardClaimed()) {
                continue;
            }
            if (instance != null && active.instanceIfPresent().filter(instance::equals).isPresent()) {
                return active;
            }
            if (fallback == null) {
                fallback = active;
            }
        }
        // A pre-1.6.5 copy has not minted an instance id yet, so a request naming one may legitimately
        // find none; a request naming none accepts the first copy, which is what every older packet did.
        return instance == null ? fallback : null;
    }

    // ---------------------------------------------------------------------------------------------
    // Entry points
    // ---------------------------------------------------------------------------------------------

    /**
     * The pre-1.6.5 right-click hand-off, now behind {@code legacyInteractDelivery} and reporting what
     * it did.
     *
     * <p>The old path returned silently for the wrong villager, for too few items and for a refused
     * transfer alike. It also took goods from a click the player had made to open a menu, which is why
     * it is off by default.
     */
    public static Outcome legacyInteract(ServerPlayer player, ActiveQuest active,
                                         DeliverToVillagerObjective objective, ObjectiveProgress progress,
                                         LivingEntity recipient) {
        int index = indexOf(active, progress);
        if (index < 0) {
            return Outcome.of(DeliveryResult.INVALID_REQUEST);
        }
        Outcome outcome = commit(player, DeliveryRequest.legacyInteract(active.instance(), active.questId(),
                index, recipient.getUUID()));
        if (outcome.result() != DeliveryResult.WRONG_RECIPIENT
                && outcome.result() != DeliveryResult.ALREADY_DELIVERED) {
            // A click on the wrong villager is not a refusal the player asked about, and a finished
            // delivery is not news; everything else is something they need to be told.
            player.sendSystemMessage(outcome.message());
        }
        return outcome;
    }

    /**
     * Routes one unit from the player's actual main hand to a quest, for MCA's Gift gesture.
     *
     * <p><b>Not gated on the hook's own capability flag.</b> Being called <em>is</em> the capability:
     * refusing here because a static verification was unsure would cancel MCA's gift and deliver
     * nothing, which is worse than either outcome it was trying to choose between.
     *
     * <p>Empty means "no quest wants this": the caller must leave MCA's own gift behaviour completely
     * alone. A present result means the gesture belongs to a quest, including when that result is a
     * refusal — a blocked or ambiguous quest hand-in must not fall through into an ordinary gift that
     * consumes the item without credit.
     *
     * <p>One item per invocation, from the slot the player is holding. Nothing here goes looking for an
     * equivalent stack elsewhere: holding an ordinary crossbow authorizes that crossbow, not the named
     * one two rows down.
     */
    public static Optional<Outcome> giftHeld(ServerPlayer player, Entity recipient) {
        if (!McaQuestsConfig.COMMON.enableMcaGiftDelivery.get()
                || !(player.level() instanceof ServerLevel)
                || !(recipient instanceof LivingEntity villager)) {
            return Optional.empty();
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return Optional.empty();
        }
        int heldSlot = player.getInventory().selected;
        List<DeliveryView> matching = viewsFor(player, villager).stream()
                .filter(DeliveryService::actionable)
                .filter(view -> wantsHeld(player, view, held))
                .toList();
        if (matching.isEmpty()) {
            return Optional.empty();
        }
        DeliveryView chosen = choose(player, matching).orElse(null);
        if (chosen == null) {
            return Optional.of(Outcome.of(DeliveryResult.AMBIGUOUS_DELIVERY));
        }
        DeliveryResult blocker = chosen.reasonIfAny().orElse(null);
        if (blocker != null) {
            // Quest intent exists and is blocked. Reporting it is the whole point: falling through to an
            // ordinary gift here would take the requested item and credit nothing.
            return Optional.of(Outcome.of(blocker));
        }
        return Optional.of(commit(player, DeliveryRequest.gift(chosen.instance(), chosen.questId(),
                chosen.objectiveIndex(), villager.getUUID(), heldSlot)));
    }

    /** Whether the obligation behind {@code view} is asking for the stack in the player's hand. */
    private static boolean wantsHeld(ServerPlayer player, DeliveryView view, ItemStack held) {
        return obligationOf(player, view).map(obligation -> obligation.matcher().test(held)).orElse(false);
    }

    /**
     * The single obligation a Gift means, or {@code null} when the gesture is genuinely ambiguous.
     *
     * <p>A tracked quest wins when its own match is unambiguous — that is the quest the player is
     * working on — and anything else is a question the player has to answer in the menu rather than one
     * to guess at with their items.
     */
    private static Optional<DeliveryView> choose(ServerPlayer player, List<DeliveryView> matching) {
        PlayerQuestData data = QuestCapabilities.get(player).orElse(null);
        ActiveQuest tracked = data == null ? null : data.trackedQuest().orElse(null);
        return chooseFrom(matching, tracked == null ? null : tracked.questId(),
                tracked == null ? null : tracked.instanceIfPresent().orElse(null));
    }

    /** The obligation a view was built from, for the paths that hold a view and need its matcher. */
    private static Optional<Obligation> obligationOf(ServerPlayer player, DeliveryView view) {
        PlayerQuestData data = QuestCapabilities.get(player).orElse(null);
        if (data == null) {
            return Optional.empty();
        }
        for (ActiveQuest active : data.active()) {
            if (!active.questId().equals(view.questId())) {
                continue;
            }
            if (view.instance() != null && !active.instanceIfPresent().filter(view.instance()::equals).isPresent()) {
                continue;
            }
            QuestDefinition base = QuestDefinitions.resolve(active.questId()).orElse(null);
            if (base == null) {
                continue;
            }
            QuestDefinition def = active.resolve(base);
            if (view.objectiveIndex() >= def.objectives().size()) {
                continue;
            }
            return Obligation.of(def.objectives().get(view.objectiveIndex()), view.objectiveIndex());
        }
        return Optional.empty();
    }

    /**
     * Moves the outstanding units of one {@code item_delivery} into its destination, for the add-on
     * entry point that has an objective and its progress but no active quest to name.
     *
     * <p>Shares this class's transaction and ledger rules — outstanding units only, commit once, record
     * after — so an add-on calling it cannot double-charge a partly-paid obligation.
     */
    public static Outcome transferOutstanding(ServerPlayer player, ItemDeliveryObjective objective,
                                              ObjectiveProgress progress, @Nullable Entity giver) {
        if (!objective.destination().isTransfer()) {
            return Outcome.of(DeliveryResult.INVALID_REQUEST);
        }
        int delivered = DeliveryLedger.units(objective, progress);
        int outstanding = Math.max(0, objective.count() - delivered);
        if (outstanding == 0) {
            return new Outcome(DeliveryResult.ALREADY_DELIVERED, 0, delivered, objective.count());
        }
        Container destination = objective.destination().resolveContainer(player, giver).orElse(null);
        if (destination == null) {
            return new Outcome(DeliveryResult.DESTINATION_FULL, 0, delivered, objective.count());
        }
        if (inFlight || InventoryTransfer.isCommitting()) {
            return new Outcome(DeliveryResult.STALE_REQUEST, 0, delivered, objective.count());
        }
        inFlight = true;
        try {
            // The same slot policy as every other route: the hotbar and the main inventory, never worn
            // armour and never the offhand. An add-on asking for "the outstanding units" is not asking
            // for the chestplate the player is wearing.
            IntSet slots = InventoryTransfer.defaultSourceSlots(player.getInventory());
            Predicate<ItemStack> matcher = stack -> stack.is(objective.item());
            InventoryTransfer.Plan plan = plan(player, matcher, slots, outstanding, destination);
            if (plan == null) {
                return new Outcome(DeliveryResult.DESTINATION_FULL, 0, delivered, objective.count());
            }
            if (!plan.commit()) {
                return new Outcome(DeliveryResult.STALE_REQUEST, 0, delivered, objective.count());
            }
            int total = DeliveryLedger.credit(progress, DeliveryLedger.fingerprintOf(objective), outstanding,
                    objective.count());
            DeliveryLedger.mirrorLegacyMarkers(objective, progress);
            return new Outcome(DeliveryResult.DELIVERY_SATISFIED, outstanding, total, objective.count());
        } finally {
            inFlight = false;
        }
    }

    /** True when the outstanding units of this {@code item_delivery} have somewhere to go right now. */
    public static boolean canTransferOutstanding(ServerPlayer player, ItemDeliveryObjective objective,
                                                 ObjectiveProgress progress, @Nullable Entity giver) {
        if (!objective.destination().isTransfer()) {
            return true;
        }
        int outstanding = Math.max(0, objective.count() - DeliveryLedger.units(objective, progress));
        if (outstanding == 0) {
            return true;
        }
        Container destination = objective.destination().resolveContainer(player, giver).orElse(null);
        // Planned under the slot policy the hand-over will actually debit, so this cannot answer "yes"
        // on the strength of stock the transfer is not allowed to touch.
        return destination != null && new InventoryTransfer.Plan(player.getInventory())
                .reserve(InventoryTransfer.defaultSourceSlots(player.getInventory()),
                        stack -> stack.is(objective.item()), outstanding, destination);
    }

    // ---------------------------------------------------------------------------------------------
    // Turn-in
    // ---------------------------------------------------------------------------------------------

    /**
     * A whole turn-in's item hand-over: planned here, committed by the caller, recorded here again.
     *
     * <p>The commit stays with the quest manager because turn-in is one transaction across several
     * obligations and has to happen between claiming the reward slot and paying the rewards. What lives
     * here is everything that must not differ from the other delivery routes: which slots may be
     * debited, how many units are owed, and what goes in the ledger afterwards.
     *
     * @param plan    the shared transaction, or {@code null} when the turn-in cannot be paid
     * @param charges what each outstanding obligation is being charged, for the ledger write
     * @param refused the delivery whose refusal the player should be told about, or {@code null} when
     *                there is nothing to say — being short of an item is not a refusal to report, but
     *                a villager with nowhere to put the goods is
     */
    public record TurnInPlan(@Nullable InventoryTransfer.Plan plan, List<Charge> charges,
                             @Nullable ItemDeliveryObjective refused) {

        /** One obligation's share of the transaction, carried out of planning for the ledger write. */
        public record Charge(int index, ItemDeliveryObjective objective, int units) {
        }

        private static TurnInPlan refused(@Nullable ItemDeliveryObjective objective) {
            return new TurnInPlan(null, List.of(), objective);
        }

        /** True when the whole hand-over can be paid and is waiting to be committed. */
        public boolean isPlanned() {
            return plan != null;
        }

        /** Moves the goods, once. False leaves every inventory exactly as it was. */
        public boolean commit() {
            return plan != null && plan.commit();
        }

        /**
         * Records the units that just moved, in the same ledger an early hand-in writes to.
         *
         * <p>Call only after {@link #commit} returned true. Written afterwards and never rolled back:
         * the goods are gone, so a reward policy that refuses the rest of this turn-in must not be able
         * to make the player pay again on the retry.
         */
        public void creditLedger(ActiveQuest active) {
            for (Charge charge : charges) {
                ObjectiveProgress progress = active.progress(charge.index());
                DeliveryLedger.credit(progress, DeliveryLedger.fingerprintOf(charge.objective()),
                        charge.units(), charge.objective().count());
                DeliveryLedger.mirrorLegacyMarkers(charge.objective(), progress);
            }
        }
    }

    /** One obligation's reservation, resolved once so a retry does not re-resolve its destination. */
    private record Reservation(int index, ItemDeliveryObjective objective, int units,
                               @Nullable Container destination, IntSet slots) {
    }

    /**
     * The one transaction that pays every outstanding item delivery on this quest, or a refusal.
     *
     * <p>Three things are true of it. It reserves only what is still <b>owed</b> — a player who has
     * already handed over four of six rods is charged two, never six again. Every obligation shares one
     * {@link InventoryTransfer.Plan}, so two objectives asking for the same item cannot both be
     * promised the same stack; that shared plan is the aggregation, because the second reservation sees
     * what the first took. And every reservation goes through {@link DeliveryRequest.Method#TURN_IN},
     * so the slot policy is the Deliver button's: the hotbar and the main inventory, never the armour
     * the player is wearing and never the offhand they did not offer.
     *
     * <p>A villager whose inventory is full refuses the hand-over and says so, rather than the player
     * paying for a transfer that cannot happen.
     */
    public static TurnInPlan planTurnIn(ServerPlayer player, QuestDefinition def, ActiveQuest active,
                                        @Nullable Entity giver) {
        if (inFlight || InventoryTransfer.isCommitting()) {
            // Reentered from inside another hand-over: the inventories are half-written, so anything
            // planned against them now would be planned against a state that never existed.
            return TurnInPlan.refused(null);
        }
        List<Reservation> reservations = new ArrayList<>();
        List<QuestObjective> objectives = def.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            if (!(objectives.get(i) instanceof ItemDeliveryObjective delivery)) {
                continue;
            }
            int outstanding = delivery.outstandingUnits(active.progress(i));
            if (outstanding == 0) {
                continue; // already paid, by an earlier deposit or by a pre-1.6.5 committed transfer
            }
            Container destination = null;
            if (delivery.destination().isTransfer()) {
                destination = delivery.destination().resolveContainer(player, giver).orElse(null);
                if (destination == null) {
                    return TurnInPlan.refused(delivery);
                }
            } else if (!delivery.consume()) {
                continue; // shown, not given: nothing to reserve
            }
            DeliveryRequest request = DeliveryRequest.turnIn(active.instance(), active.questId(), i,
                    active.villagerUuid());
            reservations.add(new Reservation(i, delivery, request.authorizedUnits(outstanding), destination,
                    authorizedSlots(player.getInventory(), request)));
        }
        // Ordinary stock first, exactly as a Deliver click does, and the whole allowlist only if that
        // cannot cover the batch: a player should not lose their named sword to a turn-in for six iron
        // swords while six plain ones sit in their pack.
        TurnInPlan preferred = reserveAll(player, reservations, true);
        return preferred.isPlanned() ? preferred : reserveAll(player, reservations, false);
    }

    /** Reserves every obligation against one plan, optionally restricted to ordinary stock. */
    private static TurnInPlan reserveAll(ServerPlayer player, List<Reservation> reservations, boolean plainOnly) {
        InventoryTransfer.Plan plan = new InventoryTransfer.Plan(player.getInventory());
        List<TurnInPlan.Charge> charges = new ArrayList<>();
        for (Reservation reservation : reservations) {
            ItemDeliveryObjective objective = reservation.objective();
            Predicate<ItemStack> matcher = stack -> stack.is(objective.item());
            IntSet slots = plainOnly
                    ? plainSlots(player.getInventory(), reservation.slots(), matcher)
                    : reservation.slots();
            if (!plan.reserve(slots, matcher, reservation.units(), reservation.destination())) {
                // Being short of an item is the player's own business and the turn-in simply does not
                // happen; a transfer that cannot be received is something they need told.
                return TurnInPlan.refused(reservation.destination() != null && !plainOnly ? objective : null);
            }
            charges.add(new TurnInPlan.Charge(reservation.index(), objective, reservation.units()));
        }
        return new TurnInPlan(plan, List.copyOf(charges), null);
    }

    /**
     * Which objective this progress belongs to, by identity — the one thing that cannot be confused.
     *
     * <p>Bounded by the resolved definition rather than by the stored progress list, because asking an
     * {@link ActiveQuest} for a progress entry grows that list, and a lookup must not change the save
     * it is searching.
     */
    private static int indexOf(ActiveQuest active, ObjectiveProgress progress) {
        QuestDefinition base = QuestDefinitions.resolve(active.questId()).orElse(null);
        if (base == null) {
            return -1;
        }
        int count = active.resolve(base).objectives().size();
        for (int i = 0; i < count; i++) {
            if (active.progress(i) == progress) {
                return i;
            }
        }
        return -1;
    }
}
