package dev.otectus.mcaquests.quest;

import dev.otectus.mcaquests.McaQuests;
import dev.otectus.mcaquests.McaQuestsConfig;
import dev.otectus.mcaquests.McaQuestsConfig.ProfessionMatchingMode;
import dev.otectus.mcaquests.api.ExternalSignalObjective;
import dev.otectus.mcaquests.api.QuestCompletionReceipt;
import dev.otectus.mcaquests.api.QuestDialogueHooks;
import dev.otectus.mcaquests.api.event.QuestAbandonedEvent;
import dev.otectus.mcaquests.api.event.QuestAcceptedEvent;
import dev.otectus.mcaquests.api.event.QuestDeclinedEvent;
import dev.otectus.mcaquests.api.event.QuestCompletedEvent;
import dev.otectus.mcaquests.api.event.QuestCompletionReceiptReadyEvent;
import dev.otectus.mcaquests.api.event.QuestFailedEvent;
import dev.otectus.mcaquests.api.event.QuestReadyEvent;
import dev.otectus.mcaquests.compat.McaCompat;
import dev.otectus.mcaquests.compat.TownsteadContentGate;
import dev.otectus.mcaquests.compat.TownsteadEvaluation;
import dev.otectus.mcaquests.compat.McaVillagerSnapshot;
import dev.otectus.mcaquests.compat.CompatProvider;
import dev.otectus.mcaquests.compat.CompatRegistry;
import dev.otectus.mcaquests.data.QuestRegistry;
import dev.otectus.mcaquests.profession.ProfessionMatcher;
import dev.otectus.mcaquests.project.ProjectManager;
import dev.otectus.mcaquests.project.state.ProjectSavedData;
import dev.otectus.mcaquests.quest.condition.QuestContext;
import dev.otectus.mcaquests.quest.dialogue.VoicePool;
import dev.otectus.mcaquests.quest.dialogue.VoicePools;
import dev.otectus.mcaquests.quest.guidance.GuidanceService;
import dev.otectus.mcaquests.quest.kingdom.KingdomQuestLifecycle;
import dev.otectus.mcaquests.network.CardObjective;
import dev.otectus.mcaquests.network.QuestCard;
import dev.otectus.mcaquests.network.QuestLogSyncS2CPacket;
import dev.otectus.mcaquests.network.QuestMenuDataS2CPacket;
import dev.otectus.mcaquests.network.QuestNetwork;
import dev.otectus.mcaquests.network.QuestReadyToastS2CPacket;
import dev.otectus.mcaquests.quest.objective.EscortEntityObjective;
import dev.otectus.mcaquests.quest.objective.ItemDeliveryObjective;
import dev.otectus.mcaquests.quest.delivery.DeliveryLedger;
import dev.otectus.mcaquests.quest.delivery.DeliveryRequest;
import dev.otectus.mcaquests.quest.delivery.DeliveryResult;
import dev.otectus.mcaquests.quest.delivery.DeliveryService;
import dev.otectus.mcaquests.quest.delivery.DeliveryView;
import dev.otectus.mcaquests.quest.objective.InventoryTransfer;
import dev.otectus.mcaquests.quest.objective.ObjectiveProgress;
import dev.otectus.mcaquests.quest.objective.ObjectiveSupport;
import dev.otectus.mcaquests.quest.objective.TownsteadObjective;
import dev.otectus.mcaquests.quest.objective.QuestObjective;
import dev.otectus.mcaquests.quest.objective.VillagerTargeted;
import dev.otectus.mcaquests.quest.target.VillagerTarget;
import dev.otectus.mcaquests.quest.situation.DynamicOfferSource;
import dev.otectus.mcaquests.quest.situation.QuestDefinitions;
import dev.otectus.mcaquests.quest.situation.SituationFocus;
import dev.otectus.mcaquests.quest.situation.SituationIds;
import dev.otectus.mcaquests.quest.situation.SituationManager;
import dev.otectus.mcaquests.quest.situation.SituationOffer;
import dev.otectus.mcaquests.quest.situation.state.SituationInstance;
import dev.otectus.mcaquests.quest.situation.state.SituationSavedData;
import dev.otectus.mcaquests.quest.reward.CurrencyReward;
import dev.otectus.mcaquests.quest.reward.ItemPoolReward;
import dev.otectus.mcaquests.quest.reward.ItemReward;
import dev.otectus.mcaquests.quest.reward.ItemRewardDelivery;
import dev.otectus.mcaquests.quest.reward.HeartsReward;
import dev.otectus.mcaquests.quest.reward.FactionStandingReward;
import dev.otectus.mcaquests.quest.reward.QuestReward;
import dev.otectus.mcaquests.quest.reward.TownsteadReward;
import dev.otectus.mcaquests.quest.template.PlaceholderResolver;
import dev.otectus.mcaquests.quest.template.ResolvedTemplate;
import dev.otectus.mcaquests.quest.template.TemplateSpec;
import dev.otectus.mcaquests.quest.escort.EscortHoldRegistry;
import dev.otectus.mcaquests.quest.turnin.GiverPresence;
import dev.otectus.mcaquests.state.ActiveQuest;
import dev.otectus.mcaquests.state.CompletionReceiptDurability;
import dev.otectus.mcaquests.state.OfferSession;
import dev.otectus.mcaquests.state.PlayerQuestData;
import dev.otectus.mcaquests.state.QuestCapabilities;
import dev.otectus.mcaquests.state.QuestHistory;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.function.IntPredicate;

/**
 * Server-authoritative quest controller: builds the menu view, and handles accept / decline /
 * turn-in / abandon (spec sections 5, 18, 19, 26). Every client-driven entry point re-resolves and
 * re-validates against server state; the client is never trusted. Reads MCA state via
 * {@link McaCompat} only.
 */
public final class QuestManager {

    private static final ResourceLocation INSTITUTIONAL_RECEIPT_CONSUMER =
            new ResourceLocation("ultima_kingdoms", "regional_civic_network");

    private QuestManager() {
    }

    // ---------------------------------------------------------------- packet entry points

    public static void openFromPacket(ServerPlayer player, UUID villagerUuid) {
        Entity villager = resolve(player, villagerUuid);
        if (villager != null) {
            sendMenu(player, villager);
        }
    }

    /** Used by the Phase 0 debug interaction. */
    public static void open(ServerPlayer player, Entity villager) {
        sendMenu(player, villager);
    }

    public static void acceptFromPacket(ServerPlayer player, UUID villagerUuid, ResourceLocation questId, boolean accept) {
        Entity villager = resolve(player, villagerUuid);
        if (villager == null) {
            return;
        }
        if (accept) {
            accept(player, villager, questId);
        } else {
            decline(player, villager, questId);
        }
        resendCurrentMenu(player, villager);
        syncLog(player);
    }

    public static void turnInFromPacket(ServerPlayer player, UUID villagerUuid, ResourceLocation questId) {
        Entity villager = resolve(player, villagerUuid);
        if (villager == null) {
            return;
        }
        turnIn(player, villager, questId);
        resendCurrentMenu(player, villager);
        syncLog(player);
    }

    public static void abandonFromPacket(ServerPlayer player, UUID villagerUuid, ResourceLocation questId) {
        Entity villager = resolve(player, villagerUuid);
        if (villager == null) {
            return;
        }
        abandon(player, villager, questId);
        resendCurrentMenu(player, villager);
        syncLog(player);
    }

    /**
     * Abandon driven from the quest log screen, where there is no villager interaction to validate
     * against: the giver may be dead, unloaded, or in another dimension. {@code find} is the
     * authorization — a client can only abandon a quest it actually holds — and the giver is resolved
     * best-effort purely so listeners see it when it happens to be around.
     */
    /**
     * Follows one of this player's active quests, or nothing when either argument is {@code null}.
     *
     * <p>The quest is looked up in the player's own state rather than taken from the packet, so a
     * client asking to follow a quest it does not hold simply follows nothing. Clicking the pin on the
     * quest already being followed clears it, which is what makes one button do both jobs.
     *
     * <p>Re-syncs the log immediately rather than waiting for the next poll, because the pin is a
     * button and a button that takes a second to visibly do anything reads as broken.
     */
    public static void track(ServerPlayer player, @Nullable UUID villagerUuid,
                             @Nullable ResourceLocation questId) {
        QuestCapabilities.get(player).ifPresent(data -> {
            if (villagerUuid == null || questId == null) {
                data.setTracked(null);
            } else {
                Optional<ActiveQuest> quest = data.find(questId, villagerUuid);
                data.setTracked(quest.filter(q -> !data.isTracked(q)).orElse(null));
            }
            syncLog(player);
        });
    }

    public static void abandonFromLog(ServerPlayer player, UUID villagerUuid, ResourceLocation questId) {
        QuestCapabilities.get(player).ifPresent(data -> data.find(questId, villagerUuid).ifPresent(active -> {
            abandon(player, active, resolveGiver(player, active), data);
            syncLog(player);
        }));
    }

    @Nullable
    private static Entity resolve(ServerPlayer player, UUID villagerUuid) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        Entity entity = level.getEntity(villagerUuid);
        return (entity != null && McaCompat.canPlayerInteract(player, entity)) ? entity : null;
    }

    // ---------------------------------------------------------------- menu construction

    public static void sendMenu(ServerPlayer player, Entity villager) {
        sendMenu(player, villager, Component.empty());
    }

    /**
     * Opens the native quest menu with its offer draw restricted to an authorized commission catalogue.
     * All ordinary giver, condition, cooldown, active-cap and target checks still run here and again on
     * acceptance. The caller supplies eligibility scope, not permission to accept any quest.
     */
    public static boolean openCommissionMenu(ServerPlayer player, Entity villager,
                                             Set<ResourceLocation> allowedQuestIds) {
        if (player.getServer() == null || !player.getServer().isSameThread()
                || player.level() != villager.level() || !McaCompat.canPlayerInteract(player, villager)) {
            return false;
        }
        sendMenu(player, villager, Component.empty(), Set.copyOf(allowedQuestIds), null);
        return true;
    }

    /** Institutional counterpart with a persisted, privately validated Ultima owner binding. */
    public static boolean openInstitutionalCommissionMenu(ServerPlayer player, Entity villager,
                                                          Set<ResourceLocation> allowedQuestIds,
                                                          String binding) {
        if (player.getServer() == null || !player.getServer().isSameThread()
                || player.level() != villager.level() || !McaCompat.canPlayerInteract(player, villager)) {
            return false;
        }
        for (ResourceLocation questId : allowedQuestIds) {
            QuestDefinition definition = QuestDefinitions.resolve(questId).orElse(null);
            if (!InstitutionalCommissionBridge.supportedDefinition(definition)) return false;
            String refusal = InstitutionalCommissionBridge.validate(player, villager, questId, binding, false);
            if (!refusal.isEmpty()) {
                player.sendSystemMessage(Component.literal(refusal));
                return false;
            }
        }
        sendMenu(player, villager, Component.empty(), Set.copyOf(allowedQuestIds), binding);
        return true;
    }

    /** Keeps a curated session curated while its own buttons refresh the open screen. */
    private static void resendCurrentMenu(ServerPlayer player, Entity villager) {
        Optional<Set<ResourceLocation>> restriction = QuestCapabilities.get(player)
                .flatMap(data -> data.offers().find(villager.getUUID()))
                .flatMap(OfferSession::restrictedQuestIds);
        String binding = QuestCapabilities.get(player)
                .flatMap(data -> data.offers().find(villager.getUUID()))
                .flatMap(OfferSession::institutionalBinding).orElse(null);
        sendMenu(player, villager, Component.empty(), restriction.orElse(null), binding);
    }

    /**
     * The villager's screen, with a line reporting whatever the player just did.
     *
     * <p>The notice travels with the cards it changed rather than only into the chat behind the screen:
     * a delivery that was refused in silence is the failure this whole area exists to fix, and a
     * message the player cannot see is barely better than none.
     */
    public static void sendMenu(ServerPlayer player, Entity villager, Component notice) {
        sendMenu(player, villager, notice, null, null);
    }

    private static void sendMenu(ServerPlayer player, Entity villager, Component notice,
                                 @Nullable Set<ResourceLocation> allowedQuestIds,
                                 @Nullable String institutionalBinding) {
        // Co-send community-project cards first so the client cache is populated before the quest menu
        // opens (drives the "View Project" button). Individual quests stay visually unchanged.
        ProjectManager.sendProjectMenu(player, villager);

        UUID villagerUuid = villager.getUUID();
        Component name = McaCompat.getVillagerDisplayName(villager);
        Component profession = McaCompat.getProfessionName(villager);
        int hearts = McaCompat.getHearts(player, villager);

        Optional<PlayerQuestData> dataOpt = QuestCapabilities.get(player);
        if (dataOpt.isEmpty()) {
            send(player, notice, QuestMenuDataS2CPacket.noQuest(villagerUuid, name, profession, hearts, QuestMenuStatus.NO_QUESTS));
            return;
        }
        PlayerQuestData data = dataOpt.get();

        // 1) Active quests relevant here: given by this villager, turn-in-able here per their mode, or
        //    waiting on a delivery this villager is the recipient of.
        List<ActiveQuest> relevant = relevantActiveQuests(player, villager, data);
        if (!relevant.isEmpty()) {
            List<QuestCard> cards = new ArrayList<>();
            boolean anyReady = false;
            for (ActiveQuest active : relevant) {
                Optional<QuestDefinition> defOpt = QuestDefinitions.resolve(active.questId());
                if (defOpt.isEmpty()) {
                    continue; // definition disappeared on a datapack reload; handled below if none are left
                }
                QuestDefinition def = active.resolve(defOpt.get());
                boolean complete = isComplete(player, def, active);
                boolean ready = complete && canTurnInAt(active, def, villager);
                anyReady |= ready;
                String state = ready ? QuestDefinition.READY : QuestDefinition.IN_PROGRESS;
                PlaceholderResolver resolver = active.textResolver(player);
                Component dialogue = QuestDialogueHooks.resolve(player, villager, def, state,
                        def.dialogueOr(state, def.title(resolver), resolver));
                // A finished quest brought to a villager who cannot take it showed nothing but Abandon,
                // and said nothing about where it should go. The status stays IN_PROGRESS — it genuinely
                // is not turn-in-able here — and the card carries the answer.
                if (complete && !ready) {
                    Optional<Component> hint = turnInHint(active, def);
                    if (hint.isPresent()) {
                        dialogue = Component.empty().append(dialogue).append("\n").append(hint.get());
                    }
                }
                cards.add(buildCard(player, villager, def, resolver, active, state, dialogue));
            }
            if (cards.isEmpty()) {
                // The definition disappeared on a datapack reload — fail gracefully (spec section 36).
                send(player, notice, QuestMenuDataS2CPacket.noQuest(villagerUuid, name, profession, hearts, QuestMenuStatus.BLOCKED));
                return;
            }
            // The menu status is a property of the whole screen, so it follows the first card, which
            // relevantActiveQuests has already sorted to be the one that can be turned in here.
            QuestMenuStatus status = anyReady ? QuestMenuStatus.READY : QuestMenuStatus.IN_PROGRESS;
            send(player, notice, QuestMenuDataS2CPacket.cards(villagerUuid, name, profession, hearts, status, cards));
            return;
        }

        // 2) Otherwise offer an eligible quest (if any and the player is under the active cap).
        if (data.activeCount() >= McaQuestsConfig.COMMON.maxActiveQuestsPerPlayer.get()) {
            // Being full is the player's own doing, not the villager having nothing: the bare NO_QUESTS
            // line read as "I do not need anything right now" and sent players round the village looking
            // for a quest none of them could have given.
            send(player, notice, QuestMenuDataS2CPacket.cards(villagerUuid, name, profession, hearts,
                    QuestMenuStatus.NO_QUESTS, statusCard("mcaquests.status.at_cap")));
            return;
        }
        // The offers this villager is currently showing this player. Drawn once and remembered, so
        // reopening the menu shows the same quests, the same numbers and the same words — and so a
        // decline has somewhere to be recorded (0.8.0 recomputed all of it on every open, which is why
        // declining an offer brought the very same three straight back).
        List<OfferSessionService.Offer> offers = allowedQuestIds == null
                ? OfferSessionService.currentOffers(player, villager, data)
                : institutionalBinding == null
                ? OfferSessionService.currentOffers(player, villager, data, allowedQuestIds)
                : OfferSessionService.currentOffers(player, villager, data, allowedQuestIds,
                        institutionalBinding);
        if (offers.isEmpty()) {
            // "I do not need anything right now" is true but unhelpful when the reason is "you did that
            // yesterday" or "not until you have done something else first". Every quest already authors a
            // `cooldown` and a `locked` line for exactly this, and both were parsed and never shown.
            List<QuestCard> explanation = allowedQuestIds == null
                    ? whyNothingIsOffered(player, villager, data) : List.of();
            send(player, notice, explanation.isEmpty()
                    ? QuestMenuDataS2CPacket.noQuest(villagerUuid, name, profession, hearts, QuestMenuStatus.NO_QUESTS)
                    : QuestMenuDataS2CPacket.cards(villagerUuid, name, profession, hearts,
                            QuestMenuStatus.NO_QUESTS, explanation));
            return;
        }
        List<QuestCard> cards = new ArrayList<>();
        for (OfferSessionService.Offer offer : offers) {
            cards.add(buildCard(player, villager, offer.definition(), offer.resolver(), null,
                    QuestDefinition.OFFER, offer.dialogue(player, villager)));
        }
        // What this villager says on opening the menu, before the offers themselves. Deterministic per
        // villager per day, like the offers below it, so reopening the menu does not re-greet you.
        Component greeting = VoicePools.pick(VoicePool.GREETING,
                new QuestContext(player, villager, data, NO_QUESTS_CARD)).orElse(Component.empty());
        send(player, notice, QuestMenuDataS2CPacket.cards(villagerUuid, name, profession, hearts, greeting,
                QuestMenuStatus.OFFER, cards));
    }

    /** The card shown when a villager has nothing and no quest of theirs explains why. */
    private static final ResourceLocation NO_QUESTS_CARD = new ResourceLocation(McaQuests.MOD_ID, "no_quests");

    /**
     * The villager's own explanation for having nothing to offer.
     *
     * <p>Prefers a quest on cooldown over a locked one, and among quests on cooldown the one coming back
     * soonest — "come back tomorrow" is more use than "there is a thing you cannot do yet".
     *
     * <p>Rendered as a card under {@link QuestMenuStatus#NO_QUESTS}, which adds no buttons.
     *
     * <p>This used to require the chosen quest to <em>author</em> a {@code cooldown} or {@code locked}
     * line, and its own comment claimed "every quest already authors" both. None did — not one of the
     * 262 bundled quests, and neither key existed in either locale — so this method searched, found
     * candidates, rejected every one of them for having nothing to say, and returned empty every single
     * time. Every busy villager in the game said "I do not need anything right now."
     *
     * <p>The line is now looked for in three places, in order: the quest's own dialogue, the shared
     * {@link VoicePools} for that state, and — when a villager has nothing at all rather than something
     * withheld — the {@code no_quests} pool. Only if all three are silent does this return empty and
     * the flat status line show, exactly as before.
     */
    private static List<QuestCard> whyNothingIsOffered(ServerPlayer player, Entity villager,
                                                       PlayerQuestData data) {
        // Everything this villager had was turned down. Said first, because the cooldown/locked search
        // below would otherwise blame some unrelated quest for a menu the player emptied themselves.
        if (data.offers().find(villager.getUUID()).map(OfferSession::hasUntilRefreshRefusals).orElse(false)) {
            return statusCard("mcaquests.status.all_declined");
        }
        OfferFilters.Pass pass = OfferFilters.Pass.of(player, villager, data);
        PlaceholderResolver resolver = PlaceholderResolver.forPlayer(player);
        QuestDefinition soonest = null;
        long soonestRemaining = Long.MAX_VALUE;
        QuestDefinition locked = null;
        for (QuestDefinition def : QuestRegistry.all()) {
            // Only this villager's own repertoire, and only the cheap giver filters: this runs on a menu
            // that is already empty, and walking every condition of every quest in the catalogue to
            // produce one line of flavour would not be worth it.
            if (!def.enabled() || !givenBy(def, pass)) {
                continue;
            }
            Optional<Long> remaining = data.history()
                    .cooldownRemaining(def.id(), pass.villagerUuid(), pass.now());
            if (remaining.isPresent() && remaining.get() < soonestRemaining) {
                soonestRemaining = remaining.get();
                soonest = def;
            }
            if (locked == null
                    && !data.history().onCooldown(def.id(), pass.villagerUuid(), pass.now())
                    && !OfferFilters.alreadyActive(data, def, pass.villagerUuid())
                    && !def.effectiveConditions().map(c -> c.test(pass.contextFor(def))).orElse(true)) {
                locked = def;
            }
        }
        QuestDefinition chosen = soonest != null ? soonest : locked;
        if (chosen == null) {
            return sharedVoiceCard(player, villager, data, VoicePool.NO_QUESTS);
        }
        String state = soonest != null ? QuestDefinition.COOLDOWN : QuestDefinition.LOCKED;
        QuestContext context = pass.contextFor(chosen);
        Component line = chosen.dialogue().containsKey(state)
                ? chosen.dialogueOr(state, chosen.title(resolver), resolver)
                : VoicePools.pick(state, context).orElse(null);
        if (line == null) {
            return sharedVoiceCard(player, villager, data, VoicePool.NO_QUESTS);
        }
        // Informational only: this card is the villager explaining why they have nothing, so it
        // carries no objectives, no rewards and no difficulty badge for a quest you cannot take.
        return List.of(new QuestCard(chosen.id(), chosen.title(resolver), Component.empty(),
                QuestDialogueHooks.resolve(player, villager, chosen, state, line),
                List.of(), List.of(), List.of(), ""));
    }

    /**
     * A card carrying only a shared voice line, for a villager with nothing to point at.
     *
     * <p>The card's id is synthetic because there is no quest behind it. Nothing consults it: the
     * NO_QUESTS status renders no buttons, so no packet is ever sent naming it.
     */
    private static List<QuestCard> sharedVoiceCard(ServerPlayer player, Entity villager,
                                                   PlayerQuestData data, String state) {
        QuestContext context = new QuestContext(player, villager, data, NO_QUESTS_CARD);
        return VoicePools.pick(state, context)
                .map(line -> List.of(new QuestCard(NO_QUESTS_CARD, Component.empty(), Component.empty(),
                        line, List.of(), List.of(), List.of(), "")))
                .orElseGet(List::of);
    }

    /**
     * A card carrying one flat explanatory line, for a state the villager is not really speaking about.
     *
     * <p>Same shape as {@link #sharedVoiceCard}: a synthetic id, no objectives and no rewards, rendered
     * under NO_QUESTS, which adds no buttons.
     */
    private static List<QuestCard> statusCard(String translationKey) {
        return List.of(new QuestCard(NO_QUESTS_CARD, Component.empty(), Component.empty(),
                Component.translatable(translationKey), List.of(), List.of(), List.of(), ""));
    }

    /**
     * Where a finished quest may be handed in, for a villager who cannot take it.
     *
     * <p>Empty for the two modes that can never reach this card: {@code any_villager} is turn-in-able
     * wherever the player is standing, and {@code self_complete} never goes through a menu at all. Empty
     * too for a {@code specified_profession} quest that names no profession — a datapack the validator
     * now rejects, but one an existing save may still be holding.
     */
    private static Optional<Component> turnInHint(ActiveQuest active, QuestDefinition def) {
        return switch (def.turnIn().mode()) {
            case ORIGINAL_GIVER -> Optional.of(Component.translatable("mcaquests.hint.turn_in.original_giver",
                    active.villagerName()));
            case SAME_PROFESSION -> Optional.of(Component.translatable("mcaquests.hint.turn_in.same_profession"));
            case SPECIFIED_PROFESSION -> def.turnIn().professions().isEmpty()
                    ? Optional.empty()
                    : Optional.of(Component.translatable("mcaquests.hint.turn_in.specified_profession",
                            professionList(def.turnIn().professions())));
            case ANY_VILLAGER, SELF_COMPLETE -> Optional.empty();
        };
    }

    /** The professions of a {@code specified_profession} turn-in, named and comma-separated. */
    private static Component professionList(List<ResourceLocation> professions) {
        MutableComponent list = Component.empty();
        for (int i = 0; i < professions.size(); i++) {
            if (i > 0) {
                list.append(", ");
            }
            list.append(DisplayNames.name(professions.get(i)));
        }
        return list;
    }

    /** Whether this villager is one of the givers {@code def} names — the cheap half of the gate. */
    private static boolean givenBy(QuestDefinition def, OfferFilters.Pass pass) {
        return (def.giver().isGeneric()
                        || ProfessionMatcher.matchesAny(def.giver().professions(), pass.profession(), pass.matching()))
                && (!def.giver().adultOnly() || pass.adult())
                && def.giver().acceptsHearts(pass.hearts());
    }

    private static QuestCard buildCard(ServerPlayer player, @Nullable Entity villager, QuestDefinition def,
                                       PlaceholderResolver resolver, @Nullable ActiveQuest active,
                                       String dialogueState) {
        // An add-on (e.g. MCA: Conversations) may voice this line in the villager's personality; falls back to
        // the quest's own static dialogue text when none is registered (QuestDialogueHooks).
        return buildCard(player, villager, def, resolver, active, dialogueState,
                QuestDialogueHooks.resolve(player, villager, def, dialogueState,
                        def.dialogueOr(dialogueState, def.title(resolver), resolver)));
    }

    /**
     * As above, with the dialogue line already decided.
     *
     * <p>Offer cards take this path, because their line was voiced once when the offer was drawn. Resolving
     * it per render is what made a villager re-tell all three of their offers every time the menu was
     * reopened — the "the story changes but the quests do not" half of the reported decline bug.
     */
    private static QuestCard buildCard(ServerPlayer player, @Nullable Entity villager, QuestDefinition def,
                                       PlaceholderResolver resolver, @Nullable ActiveQuest active,
                                       String dialogueState, Component dialogue) {
        // Situation offers reuse the chain-label slot for a "village needs help" tag so the player can tell
        // an emergent, time-limited request from a standing offer (0.8.0).
        boolean situation = def.category().map(SituationOffer.CATEGORY::equals).orElse(false);
        Component label = situation ? Component.translatable("mcaquests.situation.card_tag") : chainLabel(def, resolver);
        List<CardObjective> objectives = objectiveLines(player, def, active, villager);
        QuestMenuStatus state = cardState(dialogueState);
        // The copy's id is minted here and only here, and only for a card that actually carries a
        // delivery action: that is the whole of the lazy migration. A quest nobody ever delivers
        // anything for never grows one, so an untouched save stays untouched.
        Optional<UUID> instance = active == null ? Optional.empty()
                : objectives.stream().anyMatch(line -> line.delivery().isDelivery())
                        ? Optional.of(active.instance())
                        : active.instanceIfPresent();
        boolean deliverCompletes = active != null && villager != null
                && state == QuestMenuStatus.IN_PROGRESS
                && deliveryWouldComplete(player, def, active, villager, objectives);
        return new QuestCard(def.id(), def.title(resolver), label, dialogue,
                objectives, rewardLines(def, active),
                rewardIcons(def, active), difficultyLabel(def), instance, state, deliverCompletes);
    }

    /**
     * The dialogue state a card was built for, as the state the card is <em>in</em>.
     *
     * <p>They have always been the same fact under two names, and the client needed the second one:
     * the menu's single global status was what drew Complete on an in-progress card as soon as any
     * other card on the screen was ready.
     */
    private static QuestMenuStatus cardState(String dialogueState) {
        return switch (dialogueState) {
            case QuestDefinition.OFFER -> QuestMenuStatus.OFFER;
            case QuestDefinition.READY -> QuestMenuStatus.READY;
            case QuestDefinition.IN_PROGRESS -> QuestMenuStatus.IN_PROGRESS;
            // cooldown / locked / anything else is an explanation, not a quest you can act on.
            default -> QuestMenuStatus.NO_QUESTS;
        };
    }

    /**
     * Whether paying this card's one outstanding delivery would finish the quest here.
     *
     * <p>The basis for <b>Deliver &amp; complete</b>, and deliberately narrow. Everything else on the
     * quest must already be satisfied, nothing may be paused, this villager must be allowed to take the
     * reward, and there must be <em>exactly one</em> outstanding obligation that the player can pay in
     * full right now — a second one would need a second transaction, and the combined action names only
     * one objective. With several outstanding, each is paid by its own Deliver button and the card then
     * becomes ready in the ordinary way.
     */
    private static boolean deliveryWouldComplete(ServerPlayer player, QuestDefinition def, ActiveQuest active,
                                                 Entity villager, List<CardObjective> lines) {
        if (!(player.level() instanceof ServerLevel level) || !canTurnInAt(active, def, villager)
                || CapitalsQuestRequirements.unavailableReason(def).isPresent()
                || lines.size() != def.objectives().size()) {
            // A mismatched line count means a prepended capitals notice, which is a paused quest anyway.
            return false;
        }
        int payable = 0;
        for (int i = 0; i < def.objectives().size(); i++) {
            QuestObjective objective = def.objectives().get(i);
            ObjectiveProgress progress = active.progress(i);
            if (objective.unavailableReason(player, active, progress, level).isPresent()) {
                return false;
            }
            if (objective.isSatisfied(player, progress)) {
                continue;
            }
            CardObjective line = lines.get(i);
            if (!line.delivery().actionable() || line.remaining() <= 0
                    || line.deliverableNow() < line.remaining()) {
                return false;
            }
            payable++;
        }
        return payable == 1;
    }

    /** The relationship-arc context line for the UI (arc / "Part 2 of 4" / chapter), or empty for standalone quests. */
    private static Component chainLabel(QuestDefinition def, PlaceholderResolver resolver) {
        return def.chain().flatMap(chain -> chain.label(resolver)).orElse(Component.empty());
    }

    private static boolean isChainContinuation(QuestDefinition def) {
        return def.chain().map(chain -> chain.stage() > 1).orElse(false);
    }

    // ---------------------------------------------------------------- actions

    public static boolean accept(ServerPlayer player, Entity villager, ResourceLocation questId) {
        Optional<QuestDefinition> defOpt = QuestDefinitions.resolve(questId);
        Optional<PlayerQuestData> dataOpt = QuestCapabilities.get(player);
        if (defOpt.isEmpty() || dataOpt.isEmpty()) {
            return false;
        }
        QuestDefinition def = defOpt.get();
        PlayerQuestData data = dataOpt.get();
        UUID villagerUuid = villager.getUUID();
        OfferSession offerSession = data.offers().find(villagerUuid).orElse(null);

        // Re-validate server-side; never trust the client's offered id. Against this villager's actual
        // offer set, not the eligible pool: a crafted packet could otherwise accept a quest that was
        // declined, or that this villager never drew, simply because it would have been offerable.
        if (offerSession == null || offerSession.slots().stream()
                .noneMatch(slot -> slot.questId().equals(questId))) {
            return false;
        }
        String institutionalBinding = offerSession.institutionalBinding().orElse(null);
        if (def.institutionalCommission()) {
            if (institutionalBinding == null || !InstitutionalCommissionBridge.supportedDefinition(def)
                    || offerSession.packGeneration() != QuestRegistry.generation()
                    || offerSession.restrictedQuestIds().filter(ids -> ids.contains(questId)).isEmpty()) {
                return false;
            }
        } else if (institutionalBinding != null) {
            return false;
        }
        // ...and re-run the offer gate at this exact moment. A session remembers its cards for up to
        // offerRefreshTicks, and the world moves under them: a situation closes, a cooldown starts. An
        // offer that has gone says so rather than leaving a button that silently does nothing.
        OfferFilters.Result offerable = OfferFilters.explain(OfferFilters.Pass.of(player, villager, data), def);
        if (!offerable.passes()) {
            if (McaQuestsConfig.COMMON.questChatMessages.get()) {
                player.sendSystemMessage(Component.translatable("mcaquests.message.offer_gone",
                        def.title(PlaceholderResolver.forPlayer(player))));
            }
            return false;
        }
        // ...and re-ask, at this exact moment, whether the villager this quest names still exists. The
        // offer gate answered that when the menu was built; a relative can die, or the situation's focal
        // villager can be cured, between the menu opening and the button being clicked. Refusing here is
        // what stops a phantom being bound into the objective's progress permanently.
        Optional<Component> unresolvable = OfferFilters.unofferableComponent(
                new QuestContext(player, villager, data, def.id()), def);
        if (unresolvable.isPresent()) {
            if (McaQuestsConfig.COMMON.questChatMessages.get()) {
                player.sendSystemMessage(Component.translatable("mcaquests.message.target_gone",
                        unresolvable.get()));
            }
            return false;
        }
        if (data.activeCount() >= McaQuestsConfig.COMMON.maxActiveQuestsPerPlayer.get()
                || data.byVillager(villagerUuid).size() >= McaQuestsConfig.COMMON.maxActiveQuestsPerVillager.get()) {
            return false;
        }

        // For a template quest, freeze the resolved values now (deterministic — identical to the offer
        // shown today) so objectives/rewards never reroll for this accepted copy.
        ResolvedTemplate frozen = null;
        QuestDefinition accepted = def;
        String mcaName = McaCompat.getPlayerName(player);
        PlaceholderResolver resolver = PlaceholderResolver.forPlayerName(mcaName);
        if (def.isTemplate()) {
            TemplateSpec spec = def.template().get();
            // Prefer the values the player was actually shown. This used to re-resolve from scratch and
            // rely on the resolution being deterministic per world day — which stops being true the moment
            // the day ticks over between the menu opening and Accept being clicked, at which point the
            // quest quietly became a different quest with different numbers.
            Optional<ResolvedTemplate> values = OfferSessionService
                    .slotFor(data, villagerUuid, questId)
                    .map(OfferSession.Slot::frozenValues)
                    .filter(java.util.Objects::nonNull)
                    .or(() -> spec.resolveValues(new QuestContext(player, villager, data, def.id())));
            Optional<TemplateSpec.Concrete> concrete = values.flatMap(spec::toConcrete);
            if (values.isEmpty() || concrete.isEmpty()) {
                return false; // pool empty or substitution failed — cannot accept this template now
            }
            frozen = values.get();
            accepted = def.withConcrete(concrete.get());
            resolver = new PlaceholderResolver(frozen, mcaName);
        }

        if (!CapitalsQuestRequirements.allowsOffer(accepted)) {
            return false;
        }

        // A situation offer is anchored to its open instance: start time = the instance's open time so
        // the quest's deadline lands on the situation's master deadline, and the link lets completion /
        // expiry resolve the shared situation (0.8.0). A situation that closed between offer and accept
        // can no longer be accepted.
        long now = ((ServerLevel) player.level()).getGameTime();
        long startTime = now;
        // The world clock as well as game time: deadline_time is a time of day, so it has to be measured
        // on the clock sleeping and /time set move (1.5.1).
        long startDayTime = ((ServerLevel) player.level()).getDayTime();
        UUID situationLink = null;
        long situationPausedTicks = 0L;
        if (SituationIds.isSyntheticId(questId)) {
            MinecraftServer server = player.getServer();
            Optional<SituationInstance> instanceOpt = server == null
                    ? Optional.empty() : findOpenSituation(server, villager, questId);
            if (instanceOpt.isEmpty()) {
                return false;
            }
            startTime = instanceOpt.get().openGameTime();
            situationPausedTicks = instanceOpt.get().suspendedTicks(now);
            // Wind the world clock back by the same amount, so an anchored quest's time-of-day deadline
            // is the one the situation opened on rather than the one this player happened to accept on.
            startDayTime -= now - startTime;
            situationLink = instanceOpt.get().instanceId();
        }

        // Freeze the giver's village too: hearts, reputation and village-scoped titles must still land
        // when the quest is finished somewhere the giver's chunk is not loaded.
        OptionalInt villageId = dev.otectus.mcaquests.quest.reputation.QuestReputation.resolve(villager)
                .map(community -> OptionalInt.of(community.villageId()))
                .orElseGet(OptionalInt::empty);

        // This is the last authorization read before acceptance. The offer may have been open while a
        // service suspension, restitution requirement, or contract cancellation changed in Ultima.
        if (institutionalBinding != null) {
            String refusal = InstitutionalCommissionBridge.validate(
                    player, villager, questId, institutionalBinding, false);
            if (!refusal.isEmpty()) {
                player.sendSystemMessage(Component.literal(refusal));
                return false;
            }
        }

        ActiveQuest active = ActiveQuest.create(questId, villagerUuid,
                McaCompat.getVillagerDisplayName(villager),
                McaCompat.getProfessionId(villager).orElse(null),
                player.level().dimension().location(),
                startTime, OptionalLong.of(startDayTime), villageId,
                accepted.objectives().size(), frozen, situationLink);
        if (institutionalBinding != null) {
            Optional<UUID> instance = active.bindInstitutional(institutionalBinding, accepted);
            if (instance.isEmpty() || !InstitutionalCommissionBridge.accepted(
                    player, villager, questId, institutionalBinding, instance.get())) {
                player.sendSystemMessage(Component.literal("This commission could not be accepted."));
                return false;
            }
        }
        if (!KingdomQuestLifecycle.bindAtAccept(accepted, active, player, villager)) {
            if (McaQuestsConfig.COMMON.questChatMessages.get()) {
                player.sendSystemMessage(Component.translatable("mcaquests.message.offer_gone",
                        accepted.title(resolver)));
            }
            return false;
        }
        active.addSituationSuspendedTicks(situationPausedTicks);
        freezeRandomizedRewards(player, accepted, active);
        bindVillagerTargets(player, villager, accepted, active);
        data.add(active);
        // Follow what you just took on, unless you are already following something. A quest you accept
        // is almost always the one you mean to do next, and a marker that has to be switched on by
        // hand before it ever appears is a feature most players would never find.
        if (McaQuestsConfig.COMMON.autoTrackNewQuests.get()) {
            data.trackIfNothingTracked(active);
        }
        if (active.isInstitutional()
                && !CompletionReceiptDurability.flushInstitutionalAcceptance(player, active)) {
            player.sendSystemMessage(Component.literal(
                    "The commission was bound, but its quest save could not be verified. It remains active; "
                            + "reopen this menu after storage recovers."));
            return false;
        }
        if (situationLink != null && player.getServer() != null) {
            SituationSavedData.get(player.getServer()).recordParticipant(situationLink, player.getUUID());
            SituationManager.refreshParticipantRequirements(player, situationLink, data.active());
        }
        MinecraftForge.EVENT_BUS.post(new QuestAcceptedEvent(player, villager, accepted));
        TownsteadLifecycle.dispatch(player, active, villager, TownsteadLifecycle.Phase.ACCEPTED);
        McaCompat.setQuestGiverFollow(player, villager, McaQuestsConfig.COMMON.followGiverAfterAccept.get());
        if (McaQuestsConfig.COMMON.questChatMessages.get()) {
            Component acceptLine = accepted.dialogueOr(QuestDefinition.ACCEPT,
                    Component.translatable("mcaquests.message.quest_accepted", accepted.title(resolver)), resolver);
            player.sendSystemMessage(QuestDialogueHooks.resolve(player, villager, accepted, QuestDefinition.ACCEPT,
                    acceptLine));
        }
        return true;
    }

    /**
     * Turns an offer down.
     *
     * <p>Until 1.4.3 this did nothing at all. The client sent a real decision and the server dropped it,
     * then re-rendered a menu that was recomputed from a seed nothing about the refusal had changed — so
     * the same three quests came straight back, and the reporter watched a villager re-voice all three
     * offers while refusing to withdraw any of them.
     *
     * <p>What it does now, in order:
     *
     * <ol>
     *   <li>Checks the quest is actually in this villager's offer set for this player. A client can only
     *   decline something it was genuinely offered, exactly as {@code accept} re-validates its id.</li>
     *   <li>Records the refusal and refills that one slot — never the others.</li>
     *   <li>Records {@link QuestHistory.Outcome#DECLINED}, so a pack can offer the gentler version next
     *   time through the {@code mcaquests:quest_declined} condition.</li>
     *   <li>Says the {@code decline} line the quest's author wrote, which had been parsed and thrown away
     *   since the dialogue format was introduced.</li>
     *   <li>Posts {@link QuestDeclinedEvent}.</li>
     * </ol>
     *
     * <p><b>Declining is free.</b> No hearts, no reputation, no failure, no effect on completion counts.
     * The complaint was that it did nothing, not that it should hurt. Declining a situation offer refuses
     * it for this player only and never resolves, fails or cancels the situation for anyone else.
     */
    public static boolean decline(ServerPlayer player, Entity villager, ResourceLocation questId) {
        Optional<PlayerQuestData> dataOpt = QuestCapabilities.get(player);
        Optional<QuestDefinition> defOpt = QuestDefinitions.resolve(questId);
        if (dataOpt.isEmpty() || defOpt.isEmpty()) {
            return false;
        }
        PlayerQuestData data = dataOpt.get();
        if (!OfferSessionService.decline(player, villager, data, questId)) {
            return false; // not on this villager's menu — idempotent, and never trusts the client
        }
        QuestDefinition def = defOpt.get();
        data.history().recordOutcome(def.id(), villager.getUUID(), QuestHistory.Outcome.DECLINED);
        if (McaQuestsConfig.COMMON.questChatMessages.get()) {
            PlaceholderResolver resolver = PlaceholderResolver.forPlayer(player);
            Component declineLine = def.dialogueOr(QuestDefinition.DECLINE,
                    Component.translatable("mcaquests.message.quest_declined", def.title(resolver)), resolver);
            player.sendSystemMessage(QuestDialogueHooks.resolve(player, villager, def,
                    QuestDefinition.DECLINE, declineLine));
        }
        MinecraftForge.EVENT_BUS.post(new QuestDeclinedEvent(player, villager, def));
        return true;
    }

    public static boolean turnIn(ServerPlayer player, Entity villager, ResourceLocation questId) {
        Optional<PlayerQuestData> dataOpt = QuestCapabilities.get(player);
        Optional<QuestDefinition> defOpt = QuestDefinitions.resolve(questId);
        if (dataOpt.isEmpty() || defOpt.isEmpty()) {
            return false;
        }
        PlayerQuestData data = dataOpt.get();
        QuestDefinition base = defOpt.get();
        // Find a completable copy of this quest that may be turned in at THIS villager (mode-aware). Each
        // copy resolves its own template values, so completion is checked against its concrete objectives.
        Optional<ActiveQuest> activeOpt = data.active().stream()
                .filter(aq -> aq.questId().equals(questId) && !aq.rewardClaimed()
                        && canTurnInAt(aq, base, villager) && isComplete(player, aq.resolve(base), aq))
                .findFirst();
        return activeOpt.filter(active ->
                completeQuest(player, villager, active.resolve(base), active, data)).isPresent();
    }

    /** Auto-completion for {@link TurnInMode#SELF_COMPLETE} quests; called from the progress tick. */
    public static void selfComplete(ServerPlayer player, ActiveQuest active) {
        Optional<PlayerQuestData> dataOpt = QuestCapabilities.get(player);
        Optional<QuestDefinition> defOpt = QuestDefinitions.resolve(active.questId());
        if (dataOpt.isEmpty() || defOpt.isEmpty() || active.rewardClaimed()
                || !dataOpt.get().active().contains(active)) {
            return;
        }
        QuestDefinition def = active.resolve(defOpt.get());
        if (def.turnIn().mode() != TurnInMode.SELF_COMPLETE || !isComplete(player, def, active)) {
            return;
        }
        completeQuest(player, resolveGiver(player, active), def, active, dataOpt.get());
        syncLog(player);
    }

    /**
     * Rolls every randomized reward on a freshly accepted quest and stores the result on {@code active},
     * so the amount the player is shown is the amount they are eventually paid. Runs once, here, at the
     * single point a quest becomes active — the menu, the log, reconnects, and turn-in all read the stored
     * value, so there is no way to reroll a payout by reopening the UI or retrying a packet.
     *
     * <p>A quest whose {@code currency} reward declares no difficulty inherits the quest's own
     * {@code difficulty} band, which is what lets a datapack set difficulty once at the top level.
     */
    private static void freezeRandomizedRewards(ServerPlayer player, QuestDefinition def, ActiveQuest active) {
        List<QuestReward> rewards = def.rewards();
        for (int i = 0; i < rewards.size(); i++) {
            if (rewards.get(i) instanceof CurrencyReward currency) {
                active.freezeReward(i, inheritDifficulty(currency, def).roll(player.getRandom()));
            } else if (rewards.get(i) instanceof ItemPoolReward pool) {
                int choice = pool.pick(player.getRandom());
                if (choice >= 0) {
                    active.freezeReward(i, choice);
                }
            }
        }
    }

    /**
     * Binds every villager-targeted objective to one concrete villager, once, at accept time — the same
     * freeze-and-never-reroll contract {@link #freezeRandomizedRewards} gives payouts.
     *
     * <p>This is what makes a family quest name a real person. {@code "mode": "family"} resolves through
     * MCA's family tree, which <em>prefers whichever relative is currently loaded</em>; without a binding a
     * giver with two children could have the quest log naming one, the highlight glowing another, and the
     * hand-off crediting either. Binding reads the persistent family tree, so it works even when the
     * relative is nowhere near a loaded chunk.
     *
     * <p>Only {@code family} mode is bound here: {@code self} and {@code uuid} already name one villager,
     * and {@code profession} deliberately stays live so a quest does not dead-end when the smith it happened
     * to pick wanders off. Anything that cannot resolve now is left unbound and binds lazily on its first
     * resolution (see {@code ObjectiveSupport.resolveLocked}), which is also how quests accepted before
     * this existed pick up a binding.
     */
    private static void bindVillagerTargets(ServerPlayer player, Entity villager, QuestDefinition def,
                                            ActiveQuest active) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        List<QuestObjective> objectives = def.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            QuestObjective objective = objectives.get(i);
            VillagerTarget selector;
            if (objective instanceof VillagerTargeted targeted) {
                selector = targeted.targetSelector();
            } else if (objective instanceof dev.otectus.mcaquests.quest.objective.TradeWithVillagerObjective trade
                    && trade.villager().filter(t -> t.mode() == VillagerTarget.Mode.CAPITAL_ROLE).isPresent()) {
                selector = trade.villager().get();
            } else {
                continue;
            }
            switch (selector.mode()) {
                // Binds the relative who satisfies the target's own require, not merely the first entry
                // in MCA's walk. Without the filter this wrote whatever came back — including a UUID with
                // no body anywhere in the world — into the objective's progress, permanently.
                //
                // selectRelativeForBinding, not selectRelative: the gate that let this quest be offered
                // ran when the villager's offers were drawn, and since 1.4.3 those are remembered rather
                // than recomputed. A "require": "nearby" relative has usually stopped being nearby by the
                // time the player accepts, and binding nothing left the objective uncreditable in
                // silence. Failing to bind at all is now a WARN, because it means the quest is about
                // somebody who cannot be found and the player should be told, not left at 0/1.
                case FAMILY -> {
                    java.util.Optional<UUID> bound = selector.selectRelativeForBinding(villager, level);
                    if (bound.isPresent()) {
                        active.progress(i).setTargetUuid(bound.get());
                    } else {
                        McaQuests.LOGGER.warn("[MCA: Quests] Quest '{}' objective[{}] names a {} of its "
                                        + "giver requiring '{}', and no such relative could be bound at "
                                        + "accept. The objective will report its target as unavailable "
                                        + "rather than sitting at 0.", def.id(), i,
                                selector.effectiveRelation(), selector.effectiveRequire());
                    }
                }
                // A situation offer's focal villager is fixed the moment the quest is accepted, so a
                // second infection opening later cannot silently re-point a quest already in progress.
                case SITUATION_FOCUS -> SituationFocus
                        .focalVillager(level.getServer(), active.situationInstance().orElse(null))
                        .ifPresent(active.progress(i)::setTargetUuid);
                // The person holding the office at accept, not the office. A crown that changes hands
                // mid-quest must not silently re-point an escort at whoever is on the throne now.
                case CAPITAL_ROLE -> selector.selectRelativeForBinding(villager, level)
                        .ifPresent(active.progress(i)::setTargetUuid);
                default -> {
                }
            }
        }
        freezeTownsteadBaselines(player, def, active, level);
    }

    /**
     * Takes the starting reading for every objective that measures a change, at the moment of accepting
     * rather than on the first poll a second later -- long enough for a player to hand over the bread
     * that the quest is about to ask them for (Townstead spec 5.2).
     */
    private static void freezeTownsteadBaselines(ServerPlayer player, QuestDefinition def,
                                                 ActiveQuest active, ServerLevel level) {
        List<QuestObjective> objectives = def.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            if (objectives.get(i) instanceof TownsteadObjective townstead) {
                townstead.freezeBaseline(player, active, active.progress(i), level);
            }
        }
    }

    /**
     * Plans the one transaction that pays every outstanding item delivery on this quest, telling the
     * player when a hand-over is refused for a reason they can do something about.
     *
     * <p>The planning itself belongs to {@link DeliveryService#planTurnIn}, so turn-in reserves goods
     * under the same slot policy, the same outstanding-units clamp and the same ledger rules as the
     * Deliver button and MCA's Gift. What stays here is the one thing that is the quest manager's:
     * committing that transaction at the right point of the turn-in, between claiming the reward slot
     * and paying the rewards.
     */
    private static DeliveryService.TurnInPlan prepareDeliveries(ServerPlayer player, QuestDefinition def,
                                                                ActiveQuest active, @Nullable Entity giver) {
        DeliveryService.TurnInPlan deliveries = DeliveryService.planTurnIn(player, def, active, giver);
        if (!deliveries.isPlanned() && deliveries.refused() != null) {
            player.sendSystemMessage(deliveries.refused().refusalReason(player, giver));
        }
        return deliveries;
    }

    /**
     * True when every Townstead reward on this quest could be applied right now. Only consulted when
     * {@code rewardFailureBlocksCompletion} is on; otherwise a reward that cannot apply logs once and
     * the turn-in proceeds.
     */
    private static boolean townsteadRewardsCanApply(ServerPlayer player, QuestDefinition def,
                                                    @Nullable Entity giver) {
        for (QuestReward reward : def.rewards()) {
            if (reward instanceof TownsteadReward townstead
                    && townstead.enabledByConfig()
                    && !townstead.canApply(player, giver)) {
                return false;
            }
        }
        return true;
    }

    /** What the quest knew about its giver, for the rewards that can still be granted without it. */
    private static QuestReward.RewardContext rewardContext(ActiveQuest active, QuestDefinition def) {
        return new QuestReward.RewardContext(active.villagerUuid(), active.villagerName(),
                active.dimension(), active.villageId(), def.id(),
                // This copy of the quest, so a reward whose effect must land once per acceptance —
                // and again on the next acceptance of a repeatable quest — has an identity to key on
                // (1.6.6). Minted here rather than read through instanceIfPresent(): this runs inside
                // the turn-in, which is already writing the quest state, and a key that fell back to
                // "no instance" would make two runs of a repeatable quest look like one operation.
                java.util.Optional.of(active.instance()));
    }

    /**
     * Runs one reward's grant with its failures contained. The objective items are already consumed and
     * {@code rewardClaimed} is already set by the time rewards run, so a reward that throws — an add-on's,
     * most plausibly — used to strand the quest claimed but un-completable. The rest are still paid.
     */
    private static void grantSafely(ServerPlayer player, QuestReward reward, QuestDefinition def, Runnable grant) {
        try {
            grant.run();
        } catch (Throwable t) {
            McaQuests.LOGGER.error("[MCA: Quests] reward {} of '{}' threw; continuing with the rest",
                    rewardName(reward), def.id(), t);
            // The quest has already been claimed and its delivery items consumed, so the player would
            // otherwise see a turn-in that quietly paid less than it promised. Name the reward: only an
            // admin can fix an add-on's broken grant, and only if someone tells them.
            player.sendSystemMessage(Component.translatable("mcaquests.reward.failed",
                    Component.literal(rewardName(reward))));
        }
    }

    /** A reward's registered type id, or its class name when even asking for the id throws. */
    private static String rewardName(QuestReward reward) {
        try {
            return reward.type().id().toString();
        } catch (Throwable t) {
            return reward.getClass().getSimpleName();
        }
    }

    /** {@code currency} with the quest's difficulty band filled in when it declares none of its own. */
    private static CurrencyReward inheritDifficulty(CurrencyReward currency, QuestDefinition def) {
        return currency.difficulty().isPresent()
                ? currency
                : new CurrencyReward(currency.min(), currency.max(), def.difficulty());
    }

    /**
     * Atomic, idempotent completion. Claims the reward slot first (blocks packet-spam dup), consumes
     * objective items, then grants rewards (hearts last), records cooldown/completion, and removes the
     * quest. {@code grantVillager} receives the hearts reward (may be null if the giver is gone).
     */
    private static boolean completeQuest(ServerPlayer player, Entity grantVillager,
                                         QuestDefinition def, ActiveQuest active, PlayerQuestData data) {
        if (active.rewardClaimed() || !data.active().contains(active)) {
            return false;
        }
        QuestDefinition current = QuestDefinitions.resolve(active.questId()).orElse(null);
        if (!active.institutionalShapeMatches(current)) {
            if (active.isInstitutional() || (current != null && current.institutionalCommission())) {
                player.sendSystemMessage(Component.literal(
                        "This commission is suspended because its accepted terms are unavailable."));
            }
            return false;
        }
        boolean institutional = active.isInstitutional();
        if (institutional) {
            Entity issuer = resolveGiver(player, active);
            if (issuer == null) {
                player.sendSystemMessage(Component.literal(
                        "This commission is suspended because its issuer or accepted terms are unavailable."));
                return false;
            }
            String refusal = InstitutionalCommissionBridge.validate(player, issuer, active.questId(),
                    active.institutionalBinding(), true);
            if (!refusal.isEmpty()) {
                player.sendSystemMessage(Component.literal(refusal));
                return false;
            }
            ItemReward payment = (ItemReward) def.rewards().get(0);
            if (!ItemRewardDelivery.canFitExactly(player, payment.item(), payment.count())) {
                player.sendSystemMessage(Component.literal(
                        "Make room for the full commission payment before turning in the delivery."));
                return false;
            }
        }
        if (KingdomQuestLifecycle.activeStatus(def, active, player, resolveGiver(player, active))
                != KingdomQuestLifecycle.ActiveStatus.ALLOW) {
            return false;
        }
        // Optional strictness (Townstead spec 5.5). Off by default, because refusing a turn-in the
        // player has already earned is worse than quietly skipping the villager-facing half of the
        // reward -- but a server that would rather the quest waited can say so.
        if (McaQuestsConfig.COMMON.townsteadRewardFailureBlocksCompletion.get()
                && !townsteadRewardsCanApply(player, def, grantVillager)) {
            return false;
        }
        long now = ((ServerLevel) player.level()).getGameTime();
        if (institutional && (!data.hasActiveCompletionReceiptConsumer(INSTITUTIONAL_RECEIPT_CONSUMER, now)
                || !data.canCaptureCompletionReceipt(now))) {
            player.sendSystemMessage(Component.literal(
                    "The civic receipt service is unavailable; no goods or payment were moved."));
            return false;
        }
        // A polling add-on opts this player into receipts. Standalone MCA: Quests never accumulates an
        // outbox merely because a possible future consumer could be installed. Once subscribed, refuse
        // before delivery or rewards rather than evict evidence its frozen consumer cohort has not acked.
        boolean captureCompletionReceipt = institutional || data.shouldCaptureCompletionReceipt(now);
        if (captureCompletionReceipt && !data.canCaptureCompletionReceipt(now)) {
            McaQuests.LOGGER.error("[MCA: Quests] Refusing completion of '{}' for {}: completion receipt "
                    + "outbox status is {}", def.id(), player.getUUID(), data.completionReceiptStatus());
            player.sendSystemMessage(Component.translatable("mcaquests.message.completion_receipts_unavailable"));
            return false;
        }
        // A delivery with nowhere to go always blocks, whatever the reward policy says: consuming the
        // goods into a villager who cannot hold them would take them off the player for nothing.
        DeliveryService.TurnInPlan deliveries = prepareDeliveries(player, def, active, grantVillager);
        if (!deliveries.isPlanned()) {
            return false;
        }
        active.setRewardClaimed(true);
        QuestReward.RewardContext context = rewardContext(active, def);
        if (!grantFactionRewards(player, grantVillager, def, active, context)) {
            active.setRewardClaimed(false);
            player.sendSystemMessage(Component.translatable("mcaquests.reward.faction_standing_unavailable"));
            return false;
        }
        if (!deliveries.commit()) {
            active.setRewardClaimed(false);
            return false;
        }
        // Record the units that just moved, in the same ledger an early hand-in writes to, so a reward
        // policy that refuses the rest of this turn-in cannot make the player pay twice on the retry.
        // Written after the commit and never rolled back: the goods are gone.
        deliveries.creditLedger(active);

        for (int i = 0; i < def.objectives().size(); i++) {
            QuestObjective objective = def.objectives().get(i);
            if (objective instanceof ItemDeliveryObjective) {
                continue; // the complete item hand-over was planned, committed and credited above
            }
            objective.consumeOnTurnIn(player, active.progress(i));
        }
        List<QuestReward> rewards = def.rewards();
        for (int i = 0; i < rewards.size(); i++) {
            QuestReward reward = rewards.get(i);
            if (reward instanceof HeartsReward) {
                continue; // hearts run last, below
            }
            if (reward instanceof CurrencyReward currency) {
                // Pay the amount frozen at accept time, never a fresh roll. This method is already
                // guarded by the rewardClaimed flag above, so a retried turn-in packet pays nothing twice.
                OptionalInt frozenAmount = active.frozenReward(i);
                if (frozenAmount.isPresent()) {
                    grantSafely(player, reward, def, () -> currency.grantAmount(player, frozenAmount.getAsInt()));
                    continue;
                }
            }
            if (reward instanceof ItemPoolReward pool) {
                // The entry chosen at accept time. A quest accepted before this reward existed has no
                // frozen choice, so pick one now rather than paying nothing.
                OptionalInt frozenChoice = active.frozenReward(i);
                int choice = pool.clamp(frozenChoice.isPresent() ? frozenChoice.getAsInt()
                        : pool.pick(player.getRandom()));
                grantSafely(player, reward, def, () -> pool.grantChoice(player, choice));
                continue;
            }
            if (active.isInstitutional() && reward instanceof ItemReward item) {
                if (!ItemRewardDelivery.grantExactly(player, item.item(), item.count())) {
                    active.setRewardClaimed(false);
                    player.sendSystemMessage(Component.literal(
                            "The commission payment could not be stored; completion was not recorded."));
                    return false;
                }
                continue;
            }
            if (reward instanceof FactionStandingReward faction) {
                continue; // granted durably before delivery consumption; see grantFactionRewards
            }
            grantSafely(player, reward, def, () -> reward.grant(player, grantVillager, context));
        }
        for (QuestReward reward : def.rewards()) {
            if (reward instanceof HeartsReward) {
                grantSafely(player, reward, def, () -> reward.grant(player, grantVillager, context));
            }
        }
        grantQuestReputation(player, grantVillager, def, active, "complete");
        TownsteadLifecycle.dispatch(player, active, grantVillager, TownsteadLifecycle.Phase.COMPLETED);

        data.history().recordCompletion(def.id(), active.villagerUuid());
        switch (def.repeat().type()) {
            case COOLDOWN -> data.history().setCooldownUntil(def.id(), active.villagerUuid(), now + def.cooldownTicks());
            case ONCE -> data.history().setCooldownUntil(def.id(), active.villagerUuid(), Long.MAX_VALUE);
            case REPEATABLE -> { /* immediately available again */ }
            // A period rule records the calendar period it was finished in, and additionally arms the
            // fallback cooldown. Both, deliberately: the token is the real rule, and the cooldown is
            // what still holds the quest back if the calendar becomes unreadable before the next offer.
            case PERIOD -> {
                data.history().recordPeriod(def.id(), active.villagerUuid(),
                        def.repeat().scope() == RepeatRule.RepeatScope.GIVER,
                        periodToken(player, def).orElse(""));
                data.history().setCooldownUntil(def.id(), active.villagerUuid(),
                        now + def.repeat().fallbackCooldownTicks());
            }
        }
        releaseEscortMovement(player, def, active);
        data.remove(active);
        // Capture only after the authoritative history/cooldown transition and active removal. The API
        // hides this object until the complete player snapshot is reread from the on-disk .dat file.
        if (captureCompletionReceipt) {
            QuestCompletionReceipt completionReceipt =
                    data.captureCompletionReceipt(player.getUUID(), active, now);
            if (CompletionReceiptDurability.flushPending(player, data)) {
                MinecraftForge.EVENT_BUS.post(new QuestCompletionReceiptReadyEvent(player, completionReceipt));
            }
        }
        MinecraftForge.EVENT_BUS.post(new QuestCompletedEvent(player, grantVillager, def));
        if (McaQuestsConfig.COMMON.questChatMessages.get()) {
            PlaceholderResolver resolver = active.textResolver(player);
            Component completeLine = def.dialogueOr(QuestDefinition.COMPLETE,
                    Component.translatable("mcaquests.message.quest_completed", def.title(resolver)), resolver);
            player.sendSystemMessage(QuestDialogueHooks.resolve(player, grantVillager, def, QuestDefinition.COMPLETE,
                    completeLine));
        }
        // A situation offer resolves its shared situation on the first participant's completion (0.8.0).
        active.situationInstance().ifPresent(instanceId -> {
            if (player.getServer() != null) {
                SituationManager.resolveSuccess(player.getServer(), instanceId, player);
            }
        });
        return true;
    }

    /**
     * Faction writes are a completion prerequisite: each uses a stable receipt id and crosses Ultima's
     * durability fence before objective items are consumed or the active quest can be removed.
     */
    private static boolean grantFactionRewards(ServerPlayer player, @Nullable Entity villager,
                                               QuestDefinition def, ActiveQuest active,
                                               QuestReward.RewardContext context) {
        return applyFactionRewardBatch(def.rewards(), i -> {
            FactionStandingReward faction = (FactionStandingReward) def.rewards().get(i);
            try {
                return faction.grantBound(player, villager, context, active.kingdomBinding(), i);
            } catch (Throwable failure) {
                McaQuests.LOGGER.error("[MCA: Quests] faction reward {} of '{}' was not durably applied",
                        i, def.id(), failure);
                return false;
            }
        });
    }

    static boolean applyFactionRewardBatch(List<QuestReward> rewards, IntPredicate grant) {
        for (int i = 0; i < rewards.size(); i++) {
            if (rewards.get(i) instanceof FactionStandingReward && !grant.test(i)) return false;
        }
        return true;
    }

    /**
     * Records the reputation outcome of a finished quest (spec §29.3).
     *
     * <h2>Three ways to arrive at an amount, never two applied</h2>
     *
     * <p>A quest may declare a top-level {@code reputation} block, or it may carry the legacy
     * {@code mcaquests:village_reputation} rewards, or both. When the block exists it is authoritative
     * and the legacy rewards are treated as display-only; otherwise their sum is translated into one
     * generic completion outcome. Applying both would silently double a reward the author only meant
     * once, which is exactly the sort of thing nobody notices until the numbers look wrong.
     *
     * <p>When a quest declares <b>neither</b> — which was true of 252 of the 262 bundled quests, and of
     * every quest that has ever used the documented {@code reputation} block, since none did — the
     * configured per-difficulty default applies. That is not a convenience: without it, finishing all but
     * ten of the bundled quests moved village standing by zero, and six of the seven quests gated on
     * standing were themselves among those ten. The default is a whole difficulty band rather than a flat
     * number so it lands beside the currency bands it mirrors, and setting all three to zero restores the
     * old behaviour for a pack that means it.
     *
     * <p>Failure and abandonment are unchanged and still cost nothing unless the pack says so (§29.3,
     * §33 rule 6): the default fills in a <em>reward</em>, never a penalty.
     *
     * <p>Runs inside the atomic claim flow, after the reward-claim guard and before event
     * notification, and every failure inside the bridge is contained: the player has already done the
     * work, so a reputation problem must never block the turn-in itself.
     */
    private static void grantQuestReputation(ServerPlayer player, @Nullable Entity grantVillager,
                                             QuestDefinition def, ActiveQuest active, String outcomeKey) {
        MinecraftServer server = player.getServer();
        if (server == null || !(player.level() instanceof ServerLevel)) {
            debugNoReputation(def, outcomeKey, "not a server level");
            return;
        }
        // The giver is routinely unloaded when a quest completes in the field, which used to mean no
        // reputation at all. Fall back to the village frozen at accept time, and to a resident scan for
        // quests accepted before 1.5.1 froze one.
        java.util.Optional<dev.otectus.mcaquests.quest.reputation.QuestReputation.Community> community =
                grantVillager != null
                        ? dev.otectus.mcaquests.quest.reputation.QuestReputation.resolve(grantVillager)
                        : active.community().or(() -> scanForCommunity(server, active));
        if (community.isEmpty()) {
            // No village resolves: nobody to have an opinion (§12.2). Worth a line, because from the
            // outside it is indistinguishable from the award simply not working.
            debugNoReputation(def, outcomeKey, "the giver belongs to no resolvable village");
            return;
        }

        java.util.Optional<dev.otectus.mcaquests.quest.reputation.ReputationOutcome> authored =
                switch (outcomeKey) {
                    case "fail" -> def.reputation().failOutcome();
                    case "abandon" -> def.reputation().abandonOutcome();
                    default -> def.reputation().completeOutcome();
                };

        dev.otectus.mcaquests.quest.reputation.ReputationOutcome outcome;
        if (authored.isPresent()) {
            outcome = authored.get();
        } else if ("complete".equals(outcomeKey)) {
            int amount = completionReputationAmount(def);
            if (amount == 0) {
                debugNoReputation(def, outcomeKey, "the quest authors no reputation outcome and the "
                        + "configured default for its difficulty band is 0");
                return;
            }
            outcome = dev.otectus.mcaquests.quest.reputation.ReputationOutcome.ofShorthand(amount)
                    .withDefaultIncident(dev.otectus.mcaquests.quest.reputation.QuestReputationBlock
                            .Incidents.QUEST_COMPLETED);
        } else {
            // §29.3, §33 rule 6: failing or abandoning costs nothing unless the pack says so.
            debugNoReputation(def, outcomeKey, "failure and abandonment cost nothing unless authored");
            return;
        }
        if (outcome.isNoOp()) {
            debugNoReputation(def, outcomeKey, "the authored outcome resolves to no change");
            return;
        }

        dev.otectus.mcaquests.quest.reputation.QuestReputation.award(
                dev.otectus.mcaquests.compat.ReputationAward
                        .builder(server, player.getUUID(), community.get().dimension(),
                                community.get().villageId(),
                                dev.otectus.mcaquests.quest.reputation.QuestReputation.SOURCE)
                        .delta(outcome.delta())
                        .incident(outcome.incident().orElse(null))
                        // The authored social profile, when the pack named one: quest completion is
                        // one social outcome however the goods arrived, so the profile rides the same
                        // single award rather than one per deposit (§16.1).
                        .incidentProfile(outcome.incidentProfile().orElse(null))
                        .visibility(outcome.visibility().orElse(null))
                        .tags(outcome.tags())
                        .dedupeKey(dev.otectus.mcaquests.quest.reputation.ReputationDedupe.quest(
                                def.id(), active.villagerUuid(), active.startGameTime(), outcomeKey))
                        .context("source_title", def.id().getPath())
                        .context("quest", def.id().toString())
                        .subject(active.villagerUuid(),
                                grantVillager != null
                                        ? dev.otectus.mcaquests.compat.McaCompat
                                                .getVillagerDisplayName(grantVillager).getString()
                                        : active.villagerName().getString(), "giver")
                        .build());
    }


    /**
     * The standing a completed quest is worth when it says nothing about standing itself, from the
     * difficulty band it already declares for its currency reward. A quest with no declared difficulty
     * uses the medium band, which is the same fallback {@code CurrencyReward} has always used.
     */
    static int completionReputationAmount(QuestDefinition def) {
        List<dev.otectus.mcaquests.quest.reward.VillageReputationReward> legacy = def.rewards().stream()
                .filter(dev.otectus.mcaquests.quest.reward.VillageReputationReward.class::isInstance)
                .map(dev.otectus.mcaquests.quest.reward.VillageReputationReward.class::cast).toList();
        if (legacy.isEmpty()) { return defaultCompletionReputation(def); }
        long total = legacy.stream().mapToLong(dev.otectus.mcaquests.quest.reward.VillageReputationReward::amount).sum();
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, total));
    }

    private static int defaultCompletionReputation(QuestDefinition def) {
        return switch (def.difficulty().orElse(QuestDifficulty.DEFAULT)) {
            case EASY -> McaQuestsConfig.COMMON.easyQuestReputation.get();
            case MEDIUM -> McaQuestsConfig.COMMON.mediumQuestReputation.get();
            case HARD -> McaQuestsConfig.COMMON.hardQuestReputation.get();
        };
    }

    /**
     * Says, once per turn-in and only under {@code debugLogging}, why a finished quest changed nobody's
     * opinion.
     *
     * <p>Every one of these was a bare {@code return}. That is how 262 quests came to be worth no
     * standing at all without anybody noticing: the feature was not throwing, not warning, and not
     * logging — it was declining, silently, every single time.
     */
    private static void debugNoReputation(QuestDefinition def, String outcomeKey, String why) {
        if (McaQuestsConfig.COMMON.debugLogging.get()) {
            McaQuests.LOGGER.debug("[MCA: Quests] no reputation recorded for '{}' ({}): {}",
                    def.id(), outcomeKey, why);
        }
    }

    public static boolean abandon(ServerPlayer player, Entity villager, ResourceLocation questId) {
        Optional<PlayerQuestData> dataOpt = QuestCapabilities.get(player);
        if (dataOpt.isEmpty()) {
            return false;
        }
        Optional<ActiveQuest> active = dataOpt.get().find(questId, villager.getUUID());
        return active.isPresent() && abandon(player, active.get(), villager, dataOpt.get());
    }

    /**
     * Server-authoritative, idempotent abandon — the single point every abandon path routes through.
     * Removes the quest, records an ABANDONED outcome (so {@code quest_abandoned} follow-ups can branch
     * on it), releases any escort movement, and posts {@link QuestAbandonedEvent}. No cooldown and no
     * {@code failure} outcome is applied: abandoning is free, exactly as it has always been from the
     * villager menu.
     *
     * <p>{@code villager} may be null (giver dead / unloaded / in another dimension) — the stored
     * {@link ActiveQuest} carries every identity this needs, so a quest is always abandonable even when
     * its giver is unreachable. The removal happens outside the definition lookup, so a quest whose
     * definition vanished on a datapack reload still clears.
     */
    public static boolean abandon(ServerPlayer player, ActiveQuest active, @Nullable Entity villager,
                                  PlayerQuestData data) {
        if (!data.active().contains(active)) {
            return false; // already reached a terminal state this tick — never abandon twice
        }
        // The owner contract reserves civic capacity and an honor slot. Release it durably before
        // removing the native quest; a crash after that callback can only resurrect an unpayable quest,
        // and the callback is idempotent when the player retries abandonment after restart.
        if (active.isInstitutional() && !InstitutionalCommissionBridge.cancelled(player, active.questId(),
                active.institutionalBinding(), active.instance())) {
            player.sendSystemMessage(Component.literal(
                    "This commission could not be cancelled; it remains active until storage recovers."));
            return false;
        }
        // Said before the quest disappears, because afterwards there is nothing left to explain it with.
        // Committed goods are already in somebody else's hands or already consumed, so abandoning does
        // not give them back; a player who is not told that reads the loss as a bug.
        warnOfKeptDeposits(player, active);
        data.remove(active);
        TownsteadLifecycle.dispatch(player, active, villager, TownsteadLifecycle.Phase.ABANDONED);
        active.situationInstance().ifPresent(id ->
                SituationManager.refreshParticipantRequirements(player, id, data.active()));
        QuestDefinitions.resolve(active.questId()).ifPresent(def -> {
            releaseEscortMovement(player, active.resolve(def), active);
            data.history().recordOutcome(def.id(), active.villagerUuid(), QuestHistory.Outcome.ABANDONED);
            grantQuestReputation(player, villager, active.resolve(def), active, "abandon");
            MinecraftForge.EVENT_BUS.post(new QuestAbandonedEvent(player, villager, def));
        });
        // Outside the lookup on purpose: a held escortee is frozen and invulnerable, and hanging its
        // release off a definition meant a quest whose definition had been removed left the villager
        // that way for good. What is still held is a fact about this player, not about the datapack.
        releaseRemainingHolds(player);
        return true;
    }

    /**
     * Tells a player what abandoning has just cost them, when it has cost them anything.
     *
     * <p>Only real deposits count. A {@code consume: false} proof objective was shown rather than given
     * — the goods never left the player's inventory — so warning about it would name a loss that did not
     * happen, and a warning that is sometimes wrong stops being read.
     *
     * <p>On the single funnel every abandon route reaches, so the villager menu, the quest log and any
     * add-on calling {@code abandon} all say it.
     */
    private static void warnOfKeptDeposits(ServerPlayer player, ActiveQuest active) {
        QuestDefinitions.resolve(active.questId()).ifPresent(base -> {
            QuestDefinition def = active.resolve(base);
            List<QuestObjective> objectives = def.objectives();
            int kept = 0;
            for (int i = 0; i < objectives.size(); i++) {
                QuestObjective objective = objectives.get(i);
                if (!DeliveryLedger.isProofOnly(objective)) {
                    kept += DeliveryLedger.units(objective, active.progress(i));
                }
            }
            if (kept > 0) {
                player.sendSystemMessage(Component.translatable("mcaquests.message.abandon_deposits_kept",
                        kept, def.title(active.textResolver(player))));
            }
        });
    }

    /**
     * Server-authoritative, idempotent quest failure — the single point every failure path routes
     * through (deadline / time-window / weather triggers and giver death). Records a FAILED outcome
     * (so {@code quest_failed} follow-ups can branch on it), applies the {@code failure} outcome
     * (heart penalty, then a {@code retry_after} cooldown or a permanent {@code block_retry} lock),
     * notifies the player with the quest's {@code failed} dialogue (falling back to the generic
     * message), posts {@link QuestFailedEvent}, and removes the quest. No rewards are granted, so a
     * failed quest never duplicates a completion. {@code giver} may be null (dead / unloaded); pass it
     * when known to skip a re-resolve.
     */
    public static void failQuest(ServerPlayer player, ActiveQuest active, QuestDefinition def,
                                 QuestFailedEvent.Reason reason, @Nullable Entity giver, PlayerQuestData data) {
        if (!data.active().contains(active)) {
            return; // already reached a terminal state this tick — never fail (or double-fail) twice
        }
        if (reason != QuestFailedEvent.Reason.TARGET_LOST
                && reason != QuestFailedEvent.Reason.KINGDOM_CHANGED
                && reason != QuestFailedEvent.Reason.CIVIC_BUILDING_LOST
                && isSuspended(player, def, active)) {
            // The quest cannot be played right now, so it cannot be lost right now either. Guarding the
            // funnel every failure path routes through covers deadlines, weather, and the protect /
            // escort / giver death handlers in one place.
            return;
        }
        Entity resolvedGiver = giver != null ? giver : resolveGiver(player, active);
        long now = ((ServerLevel) player.level()).getGameTime();

        data.history().recordOutcome(def.id(), active.villagerUuid(), QuestHistory.Outcome.FAILED);
        def.failure().ifPresent(failure -> {
            if (failure.failureHearts() != 0 && resolvedGiver != null) {
                McaCompat.addHearts(player, resolvedGiver, failure.failureHearts());
            }
            if (failure.blockRetry()) {
                data.history().setCooldownUntil(def.id(), active.villagerUuid(), Long.MAX_VALUE);
            } else {
                failure.retryAfterTicks().ifPresent(retry ->
                        data.history().setCooldownUntil(def.id(), active.villagerUuid(), now + retry));
            }
        });
        releaseEscortMovement(player, def, active);
        data.remove(active);
        active.situationInstance().ifPresent(id ->
                SituationManager.refreshParticipantRequirements(player, id, data.active()));
        grantQuestReputation(player, resolvedGiver, def, active, "fail");
        // Tell the client now. The per-tick resync only runs for a player who still has active quests, so
        // failing the last one left it sitting in the log and on the tracker until the next relog.
        syncLog(player);

        TownsteadLifecycle.dispatch(player, active, resolvedGiver, TownsteadLifecycle.Phase.FAILED);
        MinecraftForge.EVENT_BUS.post(new QuestFailedEvent(player, resolvedGiver, def, reason));
        if (McaQuestsConfig.COMMON.questChatMessages.get()) {
            PlaceholderResolver failResolver = active.textResolver(player);
            Component failLine = def.dialogueOr(QuestDefinition.FAILED,
                    Component.translatable("mcaquests.message.quest_failed", def.title(failResolver)),
                    failResolver);
            player.sendSystemMessage(QuestDialogueHooks.resolve(player, resolvedGiver, def, QuestDefinition.FAILED,
                    failLine));
        }
        if (McaQuestsConfig.COMMON.debugLogging.get()) {
            McaQuests.LOGGER.debug("[MCA: Quests] Failed quest '{}' for {} (reason {}).",
                    def.id(), player.getGameProfile().getName(), reason);
        }
    }

    /**
     * Whether an escort is still free to choose its escortee — true only while none has been bound.
     *
     * <p>The rule on its own, because the bug it fixes is invisible at the call site: an unbound
     * selector asked a second time answers with whoever is convenient, which for a locked escort means
     * a different villager entirely.
     */
    public static boolean escorteeSelectorAllowed(@Nullable UUID lockedTargetUuid) {
        return lockedTargetUuid == null;
    }

    /**
     * Releases every escort hold {@code player} still owns, whether or not the quest that placed it
     * could be resolved.
     *
     * <p>A loaded escortee is released here and now, through the same three calls the normal cleanup
     * makes. One that is not loaded is queued: {@code EscortHoldEvents} releases it the moment it
     * joins a level again, which is the only point at which anything can.
     */
    private static void releaseRemainingHolds(ServerPlayer player) {
        net.minecraft.server.MinecraftServer server = player.getServer();
        for (UUID held : EscortHoldRegistry.heldBy(player.getUUID())) {
            // Every level, not just the player's: an escort can end with the villager a dimension away.
            Entity escortee = null;
            if (server != null) {
                for (ServerLevel candidate : server.getAllLevels()) {
                    escortee = candidate.getEntity(held);
                    if (escortee != null) {
                        break;
                    }
                }
            }
            if (escortee instanceof LivingEntity target) {
                McaCompat.releaseVillagerHold(target);
                McaCompat.stopVillagerLeading(target);
                McaCompat.setQuestGiverFollow(player, target, false);
            } else {
                EscortHoldRegistry.enqueueRelease(held, player.getUUID());
            }
        }
    }

    /**
     * Releases any escort movement the giver/escortee was under (lead {@code WALK_TARGET} or legacy
     * {@code FOLLOW}) when a quest reaches a terminal state, so a led villager doesn't keep walking after
     * the quest completes, is abandoned, or fails. Best-effort and fail-safe: only escort objectives, only
     * a currently-loaded target.
     */
    private static void releaseEscortMovement(ServerPlayer player, QuestDefinition def, ActiveQuest active) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        for (int i = 0; i < def.objectives().size(); i++) {
            if (def.objectives().get(i) instanceof EscortEntityObjective escort) {
                resolveEscortee(escort, active.progress(i), player, active, level).ifPresent(target -> {
                    McaCompat.releaseVillagerHold(target);   // un-freeze a still-waiting (Phase A) escortee
                    McaCompat.stopVillagerLeading(target);
                    McaCompat.setQuestGiverFollow(player, target, false);
                });
            }
        }
    }

    /**
     * The escortee to clean up: the objective's locked target, or the selector's answer when the
     * objective never bound one.
     *
     * <p><b>A bound escort never falls back to the selector.</b> It used to, whenever the locked
     * villager was not loaded — and the selector is unbound, so it could hand back a completely
     * different villager, whom the cleanup then unfroze, un-led and took out of follow. The person
     * actually being escorted stayed exactly as the quest had left them. A locked escortee that is
     * not loaded resolves to nobody; {@code EscortHoldRegistry} is what makes sure they are still
     * released when they come back.
     */
    private static Optional<LivingEntity> resolveEscortee(EscortEntityObjective escort, ObjectiveProgress progress,
                                                          ServerPlayer player, ActiveQuest active, ServerLevel level) {
        UUID locked = progress.targetUuid();
        if (!escorteeSelectorAllowed(locked)) {
            return level.getEntity(locked) instanceof LivingEntity le ? Optional.of(le) : Optional.empty();
        }
        return escort.villager().resolve(player, active, level);
    }

    // ---------------------------------------------------------------- helpers

    public static boolean isComplete(ServerPlayer player, QuestDefinition def, ActiveQuest active) {
        if (active != null && active.isInstitutional()) {
            QuestDefinition current = QuestDefinitions.resolve(active.questId()).orElse(null);
            if (!active.institutionalShapeMatches(current)
                    || resolveGiver(player, active) == null) return false;
        } else if (def.institutionalCommission()) {
            return false;
        }
        if (CapitalsQuestRequirements.unavailableReason(def).isPresent()) {
            return false;
        }
        if (KingdomQuestLifecycle.activeStatus(def, active, player, resolveGiver(player, active))
                != KingdomQuestLifecycle.ActiveStatus.ALLOW) {
            return false;
        }
        ServerLevel level = (ServerLevel) player.level();
        List<QuestObjective> objectives = def.objectives();
        InventoryTransfer.Plan items = null;
        for (int i = 0; i < objectives.size(); i++) {
            QuestObjective objective = objectives.get(i);
            ObjectiveProgress progress = active.progress(i);
            // An objective that cannot be evaluated is not a satisfied one. Guarding here rather than at
            // each caller covers ready toasts, self-complete, settleProgress, turn-in and the log's
            // ready flag in one place -- every one of them asks this question and nothing else.
            if (objective.unavailableReason(player, active, progress, level).isPresent()
                    || !objective.isSatisfied(player, progress)) {
                return false;
            }
            if (objective instanceof ItemDeliveryObjective delivery
                    && (delivery.consume() || delivery.destination().isTransfer())
                    && delivery.outstandingUnits(progress) > 0) {
                if (items == null) {
                    items = new InventoryTransfer.Plan(player.getInventory());
                }
                // Only the units still owed are reserved; the deposited ones are already with the
                // villager and cannot be spent again by another row of the same quest. Reserved under
                // the turn-in slot policy too, so this cannot report a quest ready on the strength of
                // worn armour the hand-over is not allowed to take.
                if (!items.reserve(InventoryTransfer.defaultSourceSlots(player.getInventory()),
                        stack -> stack.is(delivery.item()), delivery.outstandingUnits(progress), null)) {
                    return false; // two delivery rows cannot spend the same stack twice
                }
            }
        }
        return true;
    }

    /**
     * The reason this quest cannot currently progress, if any -- the first objective that reports one
     * (Townstead spec 10.1). A suspended quest keeps its progress and frozen baselines, does not poll,
     * cannot complete, cannot be auto-failed, and resumes untouched when whatever it reads comes back.
     */
    public static Optional<Component> suspensionReason(ServerPlayer player, QuestDefinition def,
                                                       ActiveQuest active) {
        if (active != null && active.isInstitutional()) {
            QuestDefinition current = QuestDefinitions.resolve(active.questId()).orElse(null);
            if (!active.institutionalShapeMatches(current)) {
                return Optional.of(Component.literal("Commission terms changed; cancel it or contact the issuer."));
            }
            if (resolveGiver(player, active) == null) {
                return Optional.of(Component.literal("Commission issuer unavailable"));
            }
        } else if (def.institutionalCommission()) {
            return Optional.of(Component.literal("Commission ownership is missing; cancel this quest."));
        }
        Optional<Component> capitals = CapitalsQuestRequirements.unavailableReason(def);
        if (capitals.isPresent()) {
            return capitals;
        }
        ServerLevel level = (ServerLevel) player.level();
        List<QuestObjective> objectives = def.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            Optional<Component> reason = objectives.get(i)
                    .unavailableReason(player, active, active.progress(i), level);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    /** True when any objective of this quest currently reports itself unavailable. */
    /**
     * True when an objective of this quest had bound a villager and that villager is now gone for good.
     *
     * <p>Distinct from {@link #isSuspended}: every lost target is also a suspension, but most suspensions
     * are temporary (a mod uninstalled, a chunk unloaded) and recover on their own. Only a pack that set
     * {@code failure.fail_on_target_lost} asks for this to end the quest instead of pausing it.
     */
    public static boolean hasLostBoundTarget(ServerPlayer player, QuestDefinition def, ActiveQuest active) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        List<QuestObjective> objectives = def.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            if (objectives.get(i) instanceof VillagerTargeted targeted
                    && active.progress(i).targetUuid() != null
                    && ObjectiveSupport.boundTargetLost(targeted.targetSelector(), active,
                            active.progress(i), level).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSuspended(ServerPlayer player, QuestDefinition def, ActiveQuest active) {
        return suspensionReason(player, def, active).isPresent();
    }

    /** Whether {@code active} may be turned in at {@code villager}, honouring its turn-in mode (spec section 17). */
    public static boolean canTurnInAt(ActiveQuest active, QuestDefinition def, Entity villager) {
        if (!McaCompat.isMcaVillager(villager)) {
            return false;
        }
        boolean isGiver = villager.getUUID().equals(active.villagerUuid());
        return switch (def.turnIn().mode()) {
            // The fallback is "if the original giver is gone", and it now asks. Reading the flag
            // alone let a player hand a quest to the villager beside them while the giver stood
            // there too; an unloaded giver still permits it, because nothing here can tell an
            // unloaded villager from a dead one (see GiverPresence).
            case ORIGINAL_GIVER -> isGiver
                    || (giverPresence(active, villager).permitsSameProfessionFallback(
                            McaQuestsConfig.COMMON.allowTurnInToSameProfessionIfOriginalMissing.get())
                        && sameProfessionAsGiver(active, villager));
            case ANY_VILLAGER -> true;
            case SAME_PROFESSION -> isGiver || sameProfessionAsGiver(active, villager);
            case SPECIFIED_PROFESSION -> ProfessionMatcher.matchesAny(def.turnIn().professions(),
                    McaCompat.getProfessionId(villager).orElse(null), profMode());
            case SELF_COMPLETE -> false; // completed automatically, never via the menu
        };
    }

    /** Where the giver is, read from the level the candidate villager is standing in. */
    private static GiverPresence giverPresence(ActiveQuest active, Entity villager) {
        return villager.level() instanceof ServerLevel level
                ? GiverPresence.of(level, active)
                : GiverPresence.UNLOADED_OR_UNKNOWN;
    }

    private static boolean sameProfessionAsGiver(ActiveQuest active, Entity villager) {
        ResourceLocation giverProfession = active.villagerProfession();
        ResourceLocation actual = McaCompat.getProfessionId(villager).orElse(null);
        return giverProfession != null && actual != null
                && ProfessionMatcher.matches(giverProfession, actual, profMode());
    }

    private static ProfessionMatchingMode profMode() {
        return McaQuestsConfig.COMMON.professionMatchingMode.get();
    }

    /**
     * Active quests worth showing at this villager: ones it gave, ones ready and turn-in-able here, and
     * ones waiting for a delivery this villager is the authorized recipient of.
     *
     * <p>That third case is new, and it is the one that made a "take these to Rowan" quest impossible
     * to finish through the interface: Rowan neither gave the quest nor takes its reward, so the menu
     * she opened showed her own offers and no sign of the parcel the player was carrying for her.
     */
    private static List<ActiveQuest> relevantActiveQuests(ServerPlayer player, Entity villager, PlayerQuestData data) {
        Set<ActiveQuest> awaitingDelivery = Collections.newSetFromMap(new IdentityHashMap<>());
        awaitingDelivery.addAll(DeliveryService.recipientActives(player, villager));
        List<ActiveQuest> relevant = new ArrayList<>();
        for (ActiveQuest active : data.active()) {
            QuestDefinition base = QuestDefinitions.resolve(active.questId()).orElse(null);
            if (base == null) {
                continue;
            }
            QuestDefinition def = active.resolve(base);
            boolean isGiver = active.villagerUuid().equals(villager.getUUID());
            boolean turnInableHere = isComplete(player, def, active) && canTurnInAt(active, def, villager);
            if (isGiver || turnInableHere || awaitingDelivery.contains(active)) {
                relevant.add(active);
            }
        }
        // Surface a ready, turn-in-able quest ahead of an in-progress one.
        relevant.sort(Comparator.comparingInt(active -> {
            QuestDefinition base = QuestDefinitions.resolve(active.questId()).orElse(null);
            QuestDefinition def = base == null ? null : active.resolve(base);
            boolean ready = def != null && isComplete(player, def, active) && canTurnInAt(active, def, villager);
            return ready ? 0 : 1;
        }));
        return relevant;
    }

    private static Entity resolveGiver(ServerPlayer player, ActiveQuest active) {
        if (player.level() == null) {
            return null;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, active.dimension()));
        return level != null ? level.getEntity(active.villagerUuid()) : null;
    }

    /** Shared lifecycle lookup; callers must tolerate an unloaded giver. */
    @Nullable
    public static Entity resolveGiverForLifecycle(ServerPlayer player, ActiveQuest active) {
        return resolveGiver(player, active);
    }

    /**
     * The community of a giver that is not loaded and whose quest predates the frozen village id: the
     * only way left to find it is to ask which village lists that UUID as a resident. Reached once per
     * such turn-in, never on a tick path.
     */
    private static Optional<dev.otectus.mcaquests.quest.reputation.QuestReputation.Community>
            scanForCommunity(MinecraftServer server, ActiveQuest active) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, active.dimension()));
        if (level == null) {
            return Optional.empty();
        }
        OptionalInt villageId = McaCompat.findVillageOfResident(level, active.villagerUuid());
        return villageId.isPresent()
                ? Optional.of(dev.otectus.mcaquests.quest.reputation.QuestReputation
                        .inLevel(level, villageId.getAsInt()))
                : Optional.empty();
    }

    /** The open situation instance behind a synthetic offer id, scoped to the villager's village (0.8.0). */
    private static Optional<SituationInstance> findOpenSituation(MinecraftServer server, Entity villager,
                                                                ResourceLocation syntheticId) {
        Optional<ResourceLocation> sourceId = SituationIds.sourceIdOf(syntheticId);
        java.util.OptionalInt villageId = McaCompat.getHomeVillageId(villager);
        if (sourceId.isEmpty() || villageId.isEmpty()) {
            return Optional.empty();
        }
        return SituationSavedData.get(server).openInstancesInVillage(villageId.getAsInt()).stream()
                .filter(instance -> instance.defId().equals(sourceId.get()))
                .findFirst();
    }

    /**
     * Public read-only convenience overload for add-ons: the offers {@code villager} could currently give
     * {@code player}, resolving the player's quest data internally (empty when the player has none). Lets a
     * dialogue mod ask "does this villager have work for me?" without touching {@link PlayerQuestData}.
     */
    public static List<QuestDefinition> eligibleOffers(ServerPlayer player, Entity villager) {
        return QuestCapabilities.get(player)
                .map(data -> eligibleOffers(player, villager, data))
                .orElseGet(List::of);
    }

    /** True when {@code villager} has at least one eligible offer for {@code player} right now. */
    public static boolean hasEligibleOffer(ServerPlayer player, Entity villager) {
        return !eligibleOffers(player, villager).isEmpty();
    }

    /**
     * True when {@code player} has an active quest — given by, or turn-in-able at, {@code villager} — whose
     * objectives are all satisfied (ready to hand in now). Read-only.
     */
    public static boolean hasReadyTurnIn(ServerPlayer player, Entity villager) {
        return QuestCapabilities.get(player).map(data -> data.active().stream().anyMatch(active -> {
            QuestDefinition base = QuestDefinitions.resolve(active.questId()).orElse(null);
            if (base == null) {
                return false;
            }
            QuestDefinition def = active.resolve(base);
            return isComplete(player, def, active) && canTurnInAt(active, def, villager);
        })).orElse(false);
    }

    /**
     * Advances every {@link ExternalSignalObjective} in the player's active quests that matches
     * {@code signalId} (and, if the objective is villager-specific, {@code villagerUuid}) by one step, then
     * runs the same post-progress sequence as the per-tick handler (self-complete SELF_COMPLETE quests →
     * ready transitions → log sync) so a conversation-driven objective completes immediately. Generic: any
     * add-on that registers an {@code ExternalSignalObjective} type can push progress through it.
     * {@code villagerUuid} may be null when the signal is not tied to a specific villager.
     */
    public static void notifyExternalObjective(ServerPlayer player, ResourceLocation signalId,
                                               @Nullable UUID villagerUuid) {
        Optional<PlayerQuestData> dataOpt = QuestCapabilities.get(player);
        if (dataOpt.isEmpty()) {
            return;
        }
        PlayerQuestData data = dataOpt.get();
        boolean advanced = false;
        for (ActiveQuest active : new ArrayList<>(data.active())) {
            QuestDefinition base = QuestDefinitions.resolve(active.questId()).orElse(null);
            if (base == null) {
                continue;
            }
            QuestDefinition def = active.resolve(base);
            if (CapitalsQuestRequirements.unavailableReason(def).isPresent()) {
                continue;
            }
            List<QuestObjective> objectives = def.objectives();
            for (int i = 0; i < objectives.size(); i++) {
                QuestObjective objective = objectives.get(i);
                if (objective instanceof ExternalSignalObjective signal
                        && signal.matchesSignal(signalId, villagerUuid)) {
                    ObjectiveProgress progress = active.progress(i);
                    if (objective.unavailableReason(player, active, progress, player.serverLevel()).isEmpty()
                            && progress.count() < objective.required()) {
                        progress.add(1);
                        advanced = true;
                    }
                }
            }
        }
        if (!advanced) {
            return;
        }
        settleProgress(player);
    }

    /**
     * Runs the same post-progress sequence as the per-tick handler (self-complete SELF_COMPLETE quests →
     * ready transitions → log sync), so progress pushed in outside the tick loop — an external add-on
     * signal, or a villager conversation — lands on the client immediately instead of up to a second later.
     */
    public static void settleProgress(ServerPlayer player) {
        PlayerQuestData data = QuestCapabilities.get(player).orElse(null);
        if (data == null) {
            return;
        }
        for (ActiveQuest active : new ArrayList<>(data.active())) {
            QuestDefinitions.resolve(active.questId()).ifPresent(base -> {
                QuestDefinition def = active.resolve(base);
                if (def.turnIn().mode() == TurnInMode.SELF_COMPLETE && !active.rewardClaimed()
                        && isComplete(player, def, active)) {
                    selfComplete(player, active);
                }
            });
        }
        checkReadyTransitions(player);
        syncLog(player);
    }

    static List<QuestDefinition> eligibleOffers(ServerPlayer player, Entity villager, PlayerQuestData data) {
        // One pass object for the whole sweep: the giver's profession, age, hearts and MCA snapshot are
        // read once and reused by every filter across every candidate quest (Phase 1 section 3).
        return eligibleOffers(OfferFilters.Pass.of(player, villager, data));
    }

    /** As above, reusing a {@link OfferFilters.Pass} the caller has already built. */
    static List<QuestDefinition> eligibleOffers(OfferFilters.Pass pass) {
        List<QuestDefinition> filtered = QuestRegistry.all().stream()
                .filter(def -> OfferFilters.passes(pass, def))
                .sorted(Comparator.comparing(def -> def.id().toString()))
                .toList();
        // Static quests first, then any open situations this villager can surface (0.8.0). Situation
        // offers compete in the same selection/shaping pipeline below, and since 1.4.3 they pass through
        // the same filter chain rather than being appended past it.
        List<QuestDefinition> eligible = new ArrayList<>(collapseChainsToFurthestStage(filtered));
        eligible.addAll(DynamicOfferSource.collect(pass));
        return eligible;
    }

    /**
     * Within each chain, keep only the furthest unlocked stage so a villager never offers an earlier
     * and a later stage of the same arc at once (e.g. a still-on-cooldown stage 1 alongside stage 2).
     * Standalone quests and same-stage branches pass through unchanged. Deterministic.
     */
    private static List<QuestDefinition> collapseChainsToFurthestStage(List<QuestDefinition> defs) {
        Map<String, Integer> maxStage = new HashMap<>();
        for (QuestDefinition def : defs) {
            def.chain().ifPresent(chain -> maxStage.merge(chain.chain(), chain.stage(), Math::max));
        }
        return defs.stream()
                .filter(def -> def.chain()
                        .map(chain -> chain.stage() == maxStage.get(chain.chain()))
                        .orElse(true))
                .toList();
    }

    /**
     * Deterministic, server-authoritative offer selection from the eligible pool. Candidates are grouped
     * into priority tiers ({@link #effectivePriority}: a chain continuation defaults above standalone, and a
     * datapack can override with {@code priority}); the highest tiers fill slots first, and within a tier
     * each quest is weighted by its context-sensitive {@link QuestDefinition#effectiveWeight}. One
     * {@link McaVillagerSnapshot} is shared across the weight evaluations.
     *
     * <p>The seed is supplied rather than derived here, because <em>when</em> a villager's offers change is
     * now {@link OfferSessionService}'s decision and not a side effect of the world day rolling over.
     */
    static List<QuestDefinition> selectOffers(OfferFilters.Pass pass, List<QuestDefinition> eligible,
                                              long seed) {
        int slots = McaQuestsConfig.COMMON.offersPerVillager.get();
        ToIntFunction<QuestDefinition> weightFn = weightFn(pass);
        List<QuestDefinition> chosen = new ArrayList<>();
        List<Integer> tiers = eligible.stream().map(QuestManager::effectivePriority).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        Set<String> usedGroups = new HashSet<>();
        for (int tier : tiers) {
            if (chosen.size() >= slots) {
                break;
            }
            List<QuestDefinition> bucket = eligible.stream().filter(def -> effectivePriority(def) == tier).toList();
            chosen.addAll(pickDiverse(bucket, weightFn, seed, slots - chosen.size(), usedGroups));
        }
        return chosen;
    }

    /** A quest's context-sensitive weight in this pass, sharing the pass's one MCA snapshot. */
    static ToIntFunction<QuestDefinition> weightFn(OfferFilters.Pass pass) {
        return def -> def.effectiveWeight(pass.contextFor(def));
    }

    /**
     * Weighted selection within one priority tier, taking at most one quest per
     * {@link QuestDefinition#offerGroup() offer group} before allowing seconds (spec §5.9).
     *
     * <p>The problem this solves is arithmetic, not aesthetic. With three slots and a catalogue in which
     * dozens of quests are "a villager needs something", plain weighted selection will regularly fill
     * every slot with the same kind of errand, and the menu stops looking like a village with things
     * going on. So the first pass draws from a pool with one representative per unused group; only if
     * slots remain does it fall back to the ordinary weighted draw over everything left.
     *
     * <p><b>Priority still wins.</b> Grouping happens inside a tier, never across tiers, so an emergency
     * cannot be crowded out by diversity. And an ungrouped quest — every pre-1.4.1 datapack — is never
     * excluded by this pass, so third-party content behaves exactly as it did.
     *
     * <p>{@code usedGroups} carries across tiers, so a need emergency at priority 8 also spends the
     * need group for the ordinary tier below it. That is deliberate: the player should not see the same
     * theme twice merely because one instance of it was urgent.
     */
    private static List<QuestDefinition> pickDiverse(List<QuestDefinition> bucket,
                                                     ToIntFunction<QuestDefinition> weightFn, long seed,
                                                     int slots, Set<String> usedGroups) {
        if (slots <= 0 || bucket.isEmpty()) {
            return List.of();
        }
        List<QuestDefinition> chosen = new ArrayList<>();
        List<QuestDefinition> remaining = new ArrayList<>(bucket);

        // First pass: at most one from each group not yet represented. Ungrouped quests all stay in the
        // pool, because "no group" is not a group and they must not shut each other out.
        while (chosen.size() < slots) {
            Set<String> seenThisPass = new HashSet<>();
            List<QuestDefinition> candidates = new ArrayList<>();
            for (QuestDefinition def : remaining) {
                String group = def.offerGroup().orElse("");
                if (group.isEmpty()) {
                    candidates.add(def);
                } else if (!usedGroups.contains(group) && seenThisPass.add(group)) {
                    candidates.add(def);
                }
            }
            if (candidates.isEmpty()) {
                break;
            }
            List<QuestDefinition> picked = WeightedPicker.pickMany(candidates, weightFn,
                    seed + chosen.size(), 1);
            if (picked.isEmpty()) {
                break;
            }
            QuestDefinition winner = picked.get(0);
            winner.offerGroup().filter(g -> !g.isEmpty()).ifPresent(usedGroups::add);
            chosen.add(winner);
            remaining.remove(winner);
        }

        // Second pass: every group has had its chance, so fill what is left on weight alone.
        if (chosen.size() < slots && !remaining.isEmpty()) {
            chosen.addAll(WeightedPicker.pickMany(remaining, weightFn, seed, slots - chosen.size()));
        }
        return chosen;
    }

    /**
     * Offer priority tier: explicit {@code priority}, else a situation offer defaults to the configured
     * {@code situationDefaultPriority} (so the village's needs stand out), else a chain continuation
     * (stage &gt; 1) defaults above standalone.
     */
    private static int effectivePriority(QuestDefinition def) {
        if (def.priority().isPresent()) {
            return def.priority().get();
        }
        if (def.category().map(SituationOffer.CATEGORY::equals).orElse(false)) {
            return McaQuestsConfig.COMMON.situationDefaultPriority.get();
        }
        return isChainContinuation(def) ? 1 : 0;
    }

    /**
     * The live Townstead calendar token for a {@code period} repeat rule, or empty when the rule is not
     * periodic or the calendar cannot be read (spec §5.6).
     */
    private static Optional<String> periodToken(ServerPlayer player, QuestDefinition def) {
        if (!def.repeat().isPeriodic()) {
            return Optional.empty();
        }
        return new TownsteadEvaluation().calendar(player.getServer())
                .flatMap(calendar -> def.repeat().period().orElseThrow().token(calendar));
    }

    /**
     * True when a {@code period} quest has already been completed in the period the world is currently
     * in (spec §5.6, §12.3).
     *
     * <p>An unreadable calendar answers <b>false</b> here rather than true. That is not a loophole: the
     * fallback cooldown armed at completion is still in force, so the quest is held back by ticks
     * instead of by a token, and the player is never permanently locked out of a seasonal quest because
     * Townstead was uninstalled for an evening.
     */
    static boolean completedThisPeriod(ServerPlayer player, PlayerQuestData data,
                                               QuestDefinition def, UUID villagerUuid) {
        if (!def.repeat().isPeriodic()) {
            return false;
        }
        String token = periodToken(player, def).orElse("");
        return data.history().completedInPeriod(def.id(), villagerUuid,
                def.repeat().scope() == RepeatRule.RepeatScope.GIVER, token);
    }

    /** ONCE-quest completion count: per-villager for chain stages (1:1 arcs), global for standalone quests. */
    static int onceCompletionCount(PlayerQuestData data, QuestDefinition def, UUID villagerUuid) {
        return def.chain().isPresent()
                ? data.history().completionCountByGiver(def.id(), villagerUuid)
                : data.history().completionCount(def.id());
    }

    /**
     * The objectives as the client renders them.
     *
     * <p>The counts used to be appended to the sentence as a literal {@code "  (3/24)"}. They are sent
     * as numbers now, so the client can draw a bar and a done/pending icon rather than parsing a
     * string it has no business parsing; the text is just the text.
     */
    private static List<CardObjective> objectiveLines(ServerPlayer player, QuestDefinition def,
                                                      @Nullable ActiveQuest active) {
        return objectiveLines(player, def, active, null);
    }

    /**
     * As above, for a screen that is standing in front of a particular villager.
     *
     * <p>{@code villager} is what turns an item obligation from a counter into an action: the delivery
     * fields say what has been handed over, what the player is carrying under the slot policy the
     * transaction will actually use, whether <em>this</em> villager may take it, and why not. Passed
     * {@code null} by the quest log, which shows the same numbers but offers no remote hand-in — a log
     * opened three villages away must not be a place goods can change hands.
     */
    private static List<CardObjective> objectiveLines(ServerPlayer player, QuestDefinition def,
                                                      @Nullable ActiveQuest active,
                                                      @Nullable Entity villager) {
        List<CardObjective> lines = new ArrayList<>();
        Optional<Component> capitals = active == null ? Optional.empty()
                : CapitalsQuestRequirements.unavailableReason(def);
        capitals.ifPresent(reason -> lines.add(new CardObjective(reason, 0, 0,
                CardObjective.State.UNAVAILABLE, ItemStack.EMPTY)));
        List<QuestObjective> objectives = def.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            QuestObjective objective = objectives.get(i);
            // Pass the objective's own progress so a villager-targeted line names the villager the quest
            // actually bound, not whichever relative happens to be loaded when the log is rebuilt.
            Component line = active != null
                    ? objective.describe(player, active, active.progress(i), (ServerLevel) player.level())
                    : objective.describe();
            ItemStack icon = objective.icon();
            if (active == null) {
                lines.add(CardObjective.offered(line, objective.required(), icon));
                continue;
            }
            if (capitals.isPresent()) {
                lines.add(new CardObjective(line, active.progress(i).count(), objective.required(),
                        CardObjective.State.UNAVAILABLE, icon));
                continue;
            }
            Optional<Component> unavailable = objective.unavailableReason(
                    player, active, active.progress(i), (ServerLevel) player.level());
            if (unavailable.isPresent()) {
                // No counter: "0/45" beside an objective nothing can advance reads as failure rather
                // than as "this is on hold", and the number would be a frozen baseline anyway.
                lines.add(new CardObjective(
                        Component.empty().append(line).append(Component.literal("  ")).append(unavailable.get()),
                        0, objective.required(), lostState(objective, active, i, player), icon));
                continue;
            }
            boolean done = objective.isSatisfied(player, active.progress(i));
            CardObjective.State state = done ? CardObjective.State.DONE : CardObjective.State.PENDING;
            CardObjective delivery = deliveryLine(player, def, active, objective, i, line, icon,
                    state, villager).orElse(null);
            lines.add(delivery != null ? delivery
                    : new CardObjective(line, objective.current(player, active.progress(i)),
                            objective.required(), state, icon));
        }
        return lines;
    }

    /**
     * An item obligation as a delivery row, or empty for every objective that is not one.
     *
     * <p>Three facts, kept apart because conflating the first two is the bug this exists to fix:
     * {@code delivered} comes from the ledger and means goods that have actually changed hands,
     * {@code available} comes from the inventory under the delivery slot policy, and the capability
     * says what the player may do about it here. A row that is finished carries no action at all — its
     * numbers still read 2/2, which is the point — and a row that is blocked carries the sentence
     * explaining it rather than a grey button with nothing to say.
     */
    private static Optional<CardObjective> deliveryLine(ServerPlayer player, QuestDefinition def,
                                                        ActiveQuest active, QuestObjective objective,
                                                        int index, Component line, ItemStack icon,
                                                        CardObjective.State state,
                                                        @Nullable Entity villager) {
        if (!(player.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        DeliveryService.Obligation obligation = DeliveryService.Obligation.of(objective, index).orElse(null);
        if (obligation == null) {
            return Optional.empty();
        }
        LivingEntity here = villager instanceof LivingEntity living ? living : null;
        if (here == null) {
            // A log entry, not a conversation. The numbers travel — a log that said 0/2 for a delivery
            // half handed over would be its own misreport — but nothing resolves a recipient or a
            // destination for a screen that offers no action: this runs on every log sync.
            int delivered = DeliveryLedger.units(objective, active.progress(index));
            int carried = InventoryTransfer.countIn(player.getInventory(),
                    InventoryTransfer.defaultSourceSlots(player.getInventory()), obligation.matcher());
            CardObjective.Delivery logCapability = CardObjective.Delivery.NONE;
            if (state == CardObjective.State.DONE) {
                logCapability = obligation.proofOnly() ? CardObjective.Delivery.SHOWN
                        : CardObjective.Delivery.SETTLED;
            }
            return Optional.of(new CardObjective(line, objective.current(player, active.progress(index)),
                    objective.required(), state, icon, delivered, carried, logCapability,
                    false, Component.empty()));
        }
        DeliveryView view = DeliveryService.view(player, level, active, def, obligation, here);
        boolean satisfied = state == CardObjective.State.DONE || view.satisfied();
        CardObjective.Delivery capability;
        Component reason = Component.empty();
        if (satisfied) {
            // Paid, and still worth counting: "Delivered: 2 / 2" is the line a player wants after
            // handing two crossbows over one at a time. A proof objective is answered rather than
            // paid, and says so, because abandoning costs nothing that was only ever shown.
            capability = view.proofOnly() ? CardObjective.Delivery.SHOWN : CardObjective.Delivery.SETTLED;
        } else if (view.deliverableHere()) {
            capability = view.proofOnly() ? CardObjective.Delivery.SHOW_HERE
                    : CardObjective.Delivery.DELIVER_HERE;
        } else {
            capability = view.proofOnly() ? CardObjective.Delivery.SHOW_BLOCKED
                    : CardObjective.Delivery.DELIVER_BLOCKED;
            reason = deliveryReason(view);
        }
        return Optional.of(new CardObjective(line, objective.current(player, active.progress(index)),
                objective.required(), state, icon, view.delivered(), view.available(), capability,
                view.giftCapable() && !satisfied, reason));
    }

    /**
     * Why a delivery cannot be paid here, in words the player can act on.
     *
     * <p>The two recipient answers are rewritten on purpose. "These goods are meant for somebody else"
     * is true but useless on a card that knows exactly who: naming them turns a grey button into
     * directions.
     */
    private static Component deliveryReason(DeliveryView view) {
        DeliveryResult reason = view.reason();
        if (reason == null) {
            return Component.empty();
        }
        if (reason == DeliveryResult.WRONG_RECIPIENT || reason == DeliveryResult.RECIPIENT_UNAVAILABLE) {
            return Component.translatable("mcaquests.delivery.visit_recipient", view.recipientName());
        }
        return reason.message();
    }

    /**
     * Hands goods over from the quest menu, and finishes the quest when the player asked for both.
     *
     * <p>The villager is re-resolved from the player's own world and reach — a client-supplied id is a
     * claim, not a lookup — and everything after that belongs to {@link DeliveryService}, which owns
     * the transaction and the ledger, and to {@link #turnIn}, which owns completion. Nothing here pays
     * a reward or writes progress of its own.
     *
     * <p>The screen is re-sent with the result on it. {@code DeliveryService} deliberately does not
     * re-send it for a request that carries an id, because this is the caller that knows what to say.
     */
    public static void deliver(ServerPlayer player, UUID villagerUuid, DeliveryRequest request,
                               boolean thenComplete) {
        Entity villager = resolve(player, villagerUuid);
        if (villager == null) {
            player.sendSystemMessage(DeliveryResult.RECIPIENT_UNAVAILABLE.message());
            return;
        }
        if (!villagerUuid.equals(request.recipientUuid())) {
            return; // the packet builds both from one field; a mismatch is not a click
        }
        DeliveryService.Outcome outcome = DeliveryService.commit(player, request);
        if (thenComplete && outcome.isSuccess()) {
            // The ordinary validated completion flow, with its own eligibility, rewards and history.
            // A refusal here leaves the committed deposit exactly where it is: the goods are gone, and
            // charging for them twice on the retry is the one thing that must not happen.
            turnIn(player, villager, request.questId());
        }
        sendMenu(player, villager, outcome.message());
        syncLog(player);
    }

    /**
     * Tells "the person this was about is gone" apart from "this cannot be read right now".
     *
     * <p>Both stop an objective advancing, and the old single {@code unavailable} flag conflated them,
     * so a quest whose villager had died looked identical to one waiting on an uninstalled mod. Only
     * a villager-targeted objective can lose a person, and {@code ObjectiveSupport.boundTargetLost} is
     * the one place that decides they have — so asking it again here cannot disagree with the sentence
     * already appended to the line.
     */
    private static CardObjective.State lostState(QuestObjective objective, ActiveQuest active, int index,
                                                 ServerPlayer player) {
        if (!(objective instanceof VillagerTargeted targeted) || !(player.level() instanceof ServerLevel level)) {
            return CardObjective.State.UNAVAILABLE;
        }
        return ObjectiveSupport.boundTargetLost(targeted.targetSelector(), active, active.progress(index), level)
                .isPresent() ? CardObjective.State.LOST : CardObjective.State.UNAVAILABLE;
    }

    /**
     * Preview stacks for a card's rewards, in the order the rewards are declared.
     *
     * <p>Cosmetic only, and deliberately never consulted when granting: a reward type that shows
     * nothing (hearts, reputation, a title) contributes nothing here and the card falls back to its
     * text line, which every reward still has.
     */
    private static List<ItemStack> rewardIcons(QuestDefinition def, @Nullable ActiveQuest active) {
        List<ItemStack> icons = new ArrayList<>();
        List<QuestReward> rewards = def.rewards();
        for (int i = 0; i < rewards.size(); i++) {
            QuestReward reward = rewards.get(i);
            OptionalInt frozen = active == null ? OptionalInt.empty() : active.frozenReward(i);
            // An offer shows everything the pool could give; an accepted quest shows only what it will.
            List<ItemStack> preview = reward instanceof ItemPoolReward pool && frozen.isPresent()
                    ? pool.previewIconsFrozen(pool.clamp(frozen.getAsInt()))
                    : reward.previewIcons();
            for (ItemStack stack : preview) {
                if (!stack.isEmpty()) {
                    icons.add(stack);
                }
            }
        }
        return icons;
    }

    /** The declared difficulty band as the client names it, or empty when the quest declares none. */
    private static String difficultyLabel(QuestDefinition def) {
        return def.difficulty()
                .map(band -> band.name().toLowerCase(java.util.Locale.ROOT))
                .orElse("");
    }

    /**
     * Reward summaries for a card. An <em>offer</em> ({@code active == null}) shows a randomized reward's
     * honest range, because no amount has been rolled yet; an <em>accepted</em> quest shows the exact
     * amount frozen at accept time, which is also what turn-in will pay. The player therefore never sees a
     * number that differs from what they receive.
     */
    private static List<Component> rewardLines(QuestDefinition def, @Nullable ActiveQuest active) {
        List<QuestReward> rewards = def.rewards();
        List<Component> lines = new ArrayList<>(rewards.size());
        for (int i = 0; i < rewards.size(); i++) {
            QuestReward reward = rewards.get(i);
            OptionalInt frozen = active == null ? OptionalInt.empty() : active.frozenReward(i);
            if (reward instanceof CurrencyReward currency) {
                lines.add(frozen.isPresent()
                        ? currency.describeFrozen(frozen.getAsInt())
                        : inheritDifficulty(currency, def).describe());
            } else if (reward instanceof ItemPoolReward pool) {
                lines.add(frozen.isPresent()
                        ? pool.describeFrozen(pool.clamp(frozen.getAsInt()))
                        : pool.describe());
            } else {
                lines.add(reward.describe());
            }
        }
        return lines;
    }


    private static void send(ServerPlayer player, QuestMenuDataS2CPacket packet) {
        QuestNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** The same send, with the result line attached to whichever screen shape was chosen. */
    private static void send(ServerPlayer player, Component notice, QuestMenuDataS2CPacket packet) {
        send(player, notice.getString().isEmpty() ? packet : packet.withNotice(notice));
    }

    /**
     * Detects quests whose objectives just became complete and notifies the player once (toast +
     * {@link QuestReadyEvent}); resets the flag if a possession objective later drops below target.
     */
    public static void checkReadyTransitions(ServerPlayer player) {
        QuestCapabilities.get(player).ifPresent(data -> {
            for (ActiveQuest active : data.active()) {
                QuestDefinitions.resolve(active.questId()).ifPresent(base -> {
                    QuestDefinition def = active.resolve(base);
                    boolean complete = isComplete(player, def, active);
                    if (complete && !active.readyNotified()) {
                        active.setReadyNotified(true);
                        MinecraftForge.EVENT_BUS.post(new QuestReadyEvent(player, def));
                        TownsteadLifecycle.dispatch(player, active, resolveGiver(player, active),
                                TownsteadLifecycle.Phase.READY);
                        // Resolve the MCA name only here (rare ready transition), not once per tick per player.
                        QuestNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                                new QuestReadyToastS2CPacket(def.title(active.textResolver(player))));
                    } else if (!complete && active.readyNotified()) {
                        active.setReadyNotified(false);
                    }
                });
            }
        });
    }

    /**
     * Who to blame when an active quest has no definition, if a compat provider can be blamed.
     *
     * <p>Two ways that happens, and both mean "paused" rather than "gone". The file failed to parse at
     * the last reload because it named content this world does not have, so {@code QuestDataLoader}
     * quarantined it; or its id sits under {@code compat/<provider>/}, the layout the conditional packs
     * use, and that pack is not mounted right now. Either way the quest keeps its progress and its
     * frozen clock, and it comes back on the reload after the content does.
     *
     * <p>Empty for an ordinary missing definition — a pack author who deleted a quest has not created a
     * compatibility problem, and calling it one would be a lie the player could not act on.
     */
    public static Optional<Component> compatSuspensionSubject(ResourceLocation questId) {
        CompatRegistry registry = CompatRegistry.get();
        Optional<CompatProvider> byPath = providerFromQuestPath(registry, questId);
        if (byPath.isPresent()) {
            return byPath.map(CompatProvider::displayName);
        }
        if (!QuestRegistry.isQuarantined(questId)) {
            return Optional.empty();
        }
        String namespace = QuestRegistry.quarantinedNamespace(questId).orElse(questId.getNamespace());
        return Optional.of(registry.forNamespace(namespace)
                .<Component>map(CompatProvider::displayName)
                .orElse(Component.literal(namespace)));
    }

    /** The provider owning a {@code compat/<provider>/…} quest path, when one is registered. */
    private static Optional<CompatProvider> providerFromQuestPath(CompatRegistry registry,
                                                                  ResourceLocation questId) {
        if (CapitalsQuestRequirements.isBundled(questId)) {
            return registry.provider("mcacapitals");
        }
        String path = questId.getPath();
        String prefix = "compat/";
        if (!path.startsWith(prefix)) {
            return Optional.empty();
        }
        int end = path.indexOf('/', prefix.length());
        return end < 0 ? Optional.empty() : registry.provider(path.substring(prefix.length(), end));
    }

    /**
     * Pushes the player's active-quest snapshot to the client for the quest log + HUD tracker.
     *
     * <p>Also the one place quest mutations are marked as needing new guidance. Every path that can
     * change where a player is being sent ends here — accept, turn-in, abandon, failure, follow, and
     * {@link #settleProgress} for everything that advances an objective — so marking here covers all of
     * them without six calls that could each be forgotten by the next path added. The recompute itself
     * runs once at end of tick ({@code QuestProgressEvents}), and the once-a-second pass stays as the
     * safety net for objectives nothing can mark on.
     */
    public static void syncLog(ServerPlayer player) {
        GuidanceService.markDirty(player);
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        QuestCapabilities.get(player).ifPresent(data -> {
            List<QuestLogEntry> entries = new ArrayList<>();
            // Resolve the MCA name once, and only when there is at least one quest to render.
            String mcaName = data.active().isEmpty() ? null : McaCompat.getPlayerName(player);
            for (ActiveQuest active : data.active()) {
                QuestDefinitions.resolve(active.questId()).ifPresentOrElse(base -> {
                    QuestDefinition def = active.resolve(base);
                    java.util.OptionalLong deadline = def.failure()
                            .map(failure -> failure.deadlineGameTime(active.startGameTime(),
                                    active.startDayTime(), level.getGameTime(), level.getDayTime()))
                            .orElse(java.util.OptionalLong.empty());
                    if (deadline.isPresent()) {
                        deadline = java.util.OptionalLong.of(deadline.getAsLong() + active.suspendedTicks());
                    }
                    PlaceholderResolver resolver = active.textResolver(mcaName);
                    entries.add(new QuestLogEntry(active.questId(), active.villagerUuid(), def.title(resolver),
                            active.villagerName(), chainLabel(def, resolver), objectiveLines(player, def, active),
                            isComplete(player, def, active), isSuspended(player, def, active),
                            data.isTracked(active), deadline,
                            TownsteadContextLines.forQuest(player, def, active)));
                }, () -> {
                    // The definition disappeared on a datapack reload (spec section 36). Still list it, under
                    // its raw id — otherwise the quest is invisible in the log yet keeps occupying an active
                    // slot, leaving the player no way to abandon it.
                    //
                    // When we know *why* it disappeared — the file is quarantined because it names content
                    // from a mod that is not installed, or it belongs to a compat pack that is not mounted —
                    // say so and mark the quest suspended rather than unknown. Suspended is the truth: the
                    // quest keeps its progress, its clock is frozen, and installing the mod brings it back.
                    Optional<Component> compat = compatSuspensionSubject(active.questId());
                    List<CardObjective> lines = compat
                            .map(subject -> List.of(new CardObjective(
                                    Component.translatable("mcaquests.quest.suspended.compat", subject),
                                    0, 0, CardObjective.State.UNAVAILABLE, ItemStack.EMPTY)))
                            .orElse(List.of());
                    entries.add(new QuestLogEntry(active.questId(), active.villagerUuid(),
                            Component.translatable("mcaquests.status.unknown_quest", active.questId().toString()),
                            active.villagerName(), Component.empty(), lines, false, compat.isPresent(),
                            data.isTracked(active), java.util.OptionalLong.empty(),
                            List.of()));
                });
            }
            QuestNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new QuestLogSyncS2CPacket(entries));
        });
    }

    // ---------------------------------------------------------------- diagnostics (/mcaquests debug)

    /**
     * Human-readable explanation of why {@code questId} is or is not offered by {@code villager} right now:
     * the per-quest gate checklist, per-villager chain progress, history counts, weight/priority, and a final
     * status. Drives {@code /mcaquests debug quest}. Read-only.
     */
    public static List<Component> explainOffer(ServerPlayer player, Entity villager, ResourceLocation questId) {
        List<Component> out = new ArrayList<>();
        QuestDefinition def = QuestDefinitions.resolve(questId).orElse(null);
        if (def == null) {
            out.add(Component.literal("Unknown quest '" + questId + "'."));
            return out;
        }
        Optional<PlayerQuestData> dataOpt = QuestCapabilities.get(player);
        if (dataOpt.isEmpty() || !(player.level() instanceof ServerLevel level)) {
            out.add(Component.literal("No quest data, or not on a server level."));
            return out;
        }
        PlayerQuestData data = dataOpt.get();
        UUID villagerUuid = villager.getUUID();
        long now = level.getGameTime();

        out.add(Component.literal("Quest " + questId + def.chain()
                .map(c -> " [chain '" + c.chain() + "' stage " + c.stage() + "]").orElse(" [standalone]")));

        ResourceLocation profession = McaCompat.getProfessionId(villager).orElse(null);
        out.add(line("enabled", def.enabled()));
        out.add(line("profession match", def.giver().isGeneric()
                || ProfessionMatcher.matchesAny(def.giver().professions(), profession, profMode())));
        out.add(line("adult ok", !def.giver().adultOnly() || McaCompat.isAdult(villager)));
        int hearts = McaCompat.getHearts(player, villager);
        out.add(line("hearts in range (" + hearts + ")", def.giver().acceptsHearts(hearts)));
        out.add(line(def.chain().isEmpty() ? "not already active with any villager" : "not already active here",
                !OfferFilters.alreadyActive(data, def, villagerUuid)));
        out.add(line("off cooldown", !data.history().onCooldown(questId, villagerUuid, now)));
        out.add(line("not a finished once-quest", def.repeat().type() != RepeatRule.RepeatType.ONCE
                || onceCompletionCount(data, def, villagerUuid) == 0));
        McaVillagerSnapshot snapshot = new McaVillagerSnapshot(player, villager);
        out.add(line("conditions + prerequisites", def.effectiveConditions()
                .map(c -> c.test(new QuestContext(player, villager, data, questId, snapshot))).orElse(true)));

        def.chain().ifPresent(c -> c.prerequisites().forEach(pre -> out.add(Component.literal(
                "    prereq " + pre + ": " + (data.history().completionCountByGiver(pre, villagerUuid) > 0
                        ? "completed with this villager" : "NOT completed with this villager")))));
        out.add(Component.literal("    history: completed " + data.history().completionCount(questId)
                + " (with this villager " + data.history().completionCountByGiver(questId, villagerUuid) + ")"
                + ", failed " + data.history().outcomeCount(questId, QuestHistory.Outcome.FAILED)
                + ", abandoned " + data.history().outcomeCount(questId, QuestHistory.Outcome.ABANDONED)));
        out.add(Component.literal("    priority " + effectivePriority(def) + ", effective weight "
                + def.effectiveWeight(new QuestContext(player, villager, data, questId, snapshot))
                + " (base " + def.weight() + ")"));

        List<QuestDefinition> eligible = eligibleOffers(player, villager, data);
        List<QuestDefinition> chosen = offeredNow(player, villager, data);
        out.add(status(offerStatus(player, villager, data, def, eligible, chosen)));
        return out;
    }

    /**
     * What this villager is offering the player right now, as definitions.
     *
     * <p>Reads the remembered offer set rather than re-running the draw. The debug command must answer
     * about the menu the player can actually see; re-drawing would produce a different, hypothetical
     * answer and quietly disagree with the game.
     */
    private static List<QuestDefinition> offeredNow(ServerPlayer player, Entity villager, PlayerQuestData data) {
        return OfferSessionService.currentOffers(player, villager, data).stream()
                .map(OfferSessionService.Offer::definition)
                .toList();
    }

    /**
     * One status line per relationship-chain stage this {@code villager} could give (by profession), grouped
     * by chain and ordered by stage. Drives the chain section of {@code /mcaquests debug villager}. Read-only.
     */
    public static List<Component> explainChainAvailability(ServerPlayer player, Entity villager) {
        List<Component> out = new ArrayList<>();
        Optional<PlayerQuestData> dataOpt = QuestCapabilities.get(player);
        if (dataOpt.isEmpty()) {
            out.add(Component.literal("No quest data for this player."));
            return out;
        }
        PlayerQuestData data = dataOpt.get();
        ResourceLocation profession = McaCompat.getProfessionId(villager).orElse(null);
        List<QuestDefinition> chainQuests = QuestRegistry.all().stream()
                .filter(def -> def.chain().isPresent())
                .filter(def -> def.giver().isGeneric()
                        || ProfessionMatcher.matchesAny(def.giver().professions(), profession, profMode()))
                .sorted(Comparator.<QuestDefinition, String>comparing(def -> def.chain().get().chain())
                        .thenComparingInt(def -> def.chain().get().stage())
                        .thenComparing(def -> def.id().toString()))
                .toList();
        if (chainQuests.isEmpty()) {
            out.add(Component.literal("chains: (none for this villager's profession)"));
            return out;
        }
        List<QuestDefinition> eligible = eligibleOffers(player, villager, data);
        List<QuestDefinition> chosen = offeredNow(player, villager, data);
        out.add(Component.literal("chains:"));
        String current = null;
        for (QuestDefinition def : chainQuests) {
            String chain = def.chain().get().chain();
            if (!chain.equals(current)) {
                current = chain;
                out.add(Component.literal("  " + chain + ":"));
            }
            out.add(Component.literal("    stage " + def.chain().get().stage() + " " + def.id().getPath()
                    + " — " + offerStatus(player, villager, data, def, eligible, chosen)));
        }
        return out;
    }

    /** Compact offer status for one quest at one villager (shared by both debug commands). */
    /**
     * Why a quest is or is not on this villager's menu, for {@code /mcaquests debug quest}.
     *
     * <p>Everything past the pool membership checks is answered by {@link OfferFilters#explain}, the same
     * chain the menu itself runs. It used to be answered by a private copy of that chain, which is exactly
     * the kind of duplication that drifts: the copy never learned about the trivially-satisfied check, so
     * the debug command would report a quest as eligible that the menu was quietly withholding.
     */
    private static String offerStatus(ServerPlayer player, Entity villager, PlayerQuestData data, QuestDefinition def,
                                      List<QuestDefinition> eligible, List<QuestDefinition> chosen) {
        if (data.hasActive(def.id(), villager.getUUID())) {
            return "ACTIVE (accepted from this villager)";
        }
        if (OfferFilters.alreadyActive(data, def, villager.getUUID())) {
            return "ACTIVE (accepted from another villager)";
        }
        if (chosen.contains(def)) {
            return "OFFERED";
        }
        if (eligible.contains(def)) {
            return "ELIGIBLE (in the pool, not drawn this slot/day)";
        }
        OfferFilters.Result result = OfferFilters.explain(OfferFilters.Pass.of(player, villager, data), def);
        if (result.passes()) {
            // It passed every per-quest filter yet is not in the pool, so the chain collapse dropped it.
            String by = def.chain().flatMap(c -> eligible.stream()
                    .filter(e -> e.chain()
                            .map(ec -> ec.chain().equals(c.chain()) && ec.stage() > c.stage()).orElse(false))
                    .map(e -> e.id().getPath()).findFirst()).orElse("a later stage");
            return "HIDDEN/SUPERSEDED (by " + by + ")";
        }
        return result.reason();
    }

    private static Component line(String label, boolean ok) {
        return Component.literal("  " + (ok ? "[ok] " : "[no] ") + label);
    }

    private static Component status(String text) {
        return Component.literal("=> " + text);
    }
}
