package dev.otectus.mcaquests.quest;

import dev.otectus.mcaquests.McaQuests;
import dev.otectus.mcaquests.McaQuestsConfig;
import dev.otectus.mcaquests.api.QuestDialogueHooks;
import dev.otectus.mcaquests.compat.McaCompat;
import dev.otectus.mcaquests.data.QuestRegistry;
import dev.otectus.mcaquests.quest.condition.QuestContext;
import dev.otectus.mcaquests.quest.situation.QuestDefinitions;
import dev.otectus.mcaquests.quest.template.PlaceholderResolver;
import dev.otectus.mcaquests.quest.template.ResolvedTemplate;
import dev.otectus.mcaquests.quest.template.TemplateSpec;
import dev.otectus.mcaquests.state.OfferSession;
import dev.otectus.mcaquests.state.PlayerQuestData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Draws a villager's offers once, remembers them, and hands the same ones back until they are due to
 * change.
 *
 * <p>Before this, every open of the quest menu recomputed the whole thing: the eligibility pass, the
 * weighted draw, template resolution and dialogue resolution. Three consequences, all of them reported as
 * bugs or nearly so.
 *
 * <ol>
 *   <li><b>Decline did nothing.</b> The draw was a pure function of (player, villager, world day). Nothing
 *   about the player changed when they refused an offer, so the same three quests came straight back.</li>
 *   <li><b>The story changed on its own.</b> Offer dialogue went through {@code QuestDialogueHooks} once
 *   per card per render. With MCA: Conversations installed, reopening the menu re-voiced all three offers
 *   — the reporter described "the context/story of every quest" changing while the quests stayed the
 *   same, and that is exactly what they were watching.</li>
 *   <li><b>{@code offerRefreshTicks} was a lie.</b> Declared, documented, and read nowhere; the refresh
 *   cadence was hardcoded to one Minecraft day by a {@code getDayTime() / 24000} in the seed.</li>
 * </ol>
 *
 * <h2>The rules</h2>
 *
 * <p><b>Drawing is rare, rendering is cheap, and rendering never draws.</b> A set is redrawn only when it
 * does not exist, when {@code offerRefreshTicks} have elapsed, or when the datapack has been reloaded
 * under it. Every other open re-validates the slots it already has and replaces only the ones that have
 * genuinely gone bad — so the offers a player is looking at are stable, card for card and word for word,
 * without being frozen against a world that moved on.
 *
 * <p><b>One slot at a time.</b> {@link WeightedPicker} draws sequentially from a shrinking pool, so
 * removing one candidate shifts every draw after it. Re-running selection with a declined quest filtered
 * out would satisfy "the declined quest is gone" while violating "the other two did not move". Replacing
 * a single slot is the only way to have both.
 *
 * <p><b>The monotonic clock.</b> Sessions age on {@code getGameTime()}, not {@code getDayTime()}, which
 * sleeping and {@code /time set} move. Sleeping through a night should not silently reroll every villager
 * in the village.
 */
public final class OfferSessionService {

    private OfferSessionService() {
    }

    /** A slot together with the definition it resolved to, ready to render. */
    public record Offer(OfferSession.Slot slot, QuestDefinition definition, PlaceholderResolver resolver) {

        /** The offer line to show: what the villager said when they said it, re-voiced only if lost. */
        public Component dialogue(ServerPlayer player, Entity villager) {
            if (slot.voicedOffer() != null) {
                return slot.voicedOffer();
            }
            return QuestDialogueHooks.resolve(player, villager, definition, QuestDefinition.OFFER,
                    definition.dialogueOr(QuestDefinition.OFFER, definition.title(resolver), resolver));
        }
    }

    /**
     * What this villager is offering this player right now.
     *
     * <p>Redraws if the set is due, re-validates it if not, and either way persists whatever it decided,
     * so the answer is the same on the next open.
     */
    public static List<Offer> currentOffers(ServerPlayer player, Entity villager, PlayerQuestData data) {
        return currentOffers(player, villager, data, null);
    }

    /** Same native offer session, restricted to an authorized caller's bounded commission catalogue. */
    public static List<Offer> currentOffers(ServerPlayer player, Entity villager, PlayerQuestData data,
                                            @javax.annotation.Nullable Set<ResourceLocation> allowedQuestIds) {
        return currentOffers(player, villager, data, allowedQuestIds, null);
    }

    /** Institutional menu session carrying the opaque owner binding through the eventual accept click. */
    public static List<Offer> currentOffers(ServerPlayer player, Entity villager, PlayerQuestData data,
                                            Set<ResourceLocation> allowedQuestIds, String binding) {
        ServerLevel level = (ServerLevel) player.level();
        long now = level.getGameTime();
        int refreshTicks = McaQuestsConfig.COMMON.offerRefreshTicks.get();
        UUID villagerUuid = villager.getUUID();

        data.offers().prune(now, refreshTicks);
        OfferSession session = data.offers().get(villagerUuid);
        session.pruneDeclines(now);

        OfferFilters.Pass pass = OfferFilters.Pass.of(player, villager, data);
        if (!session.scopeMatches(allowedQuestIds, binding)
                || session.isStale(now, refreshTicks, QuestRegistry.generation())) {
            redraw(pass, session, now, refreshTicks, allowedQuestIds, binding);
        } else {
            revalidate(pass, session, now);
        }
        return resolveAll(pass, session);
    }

    /**
     * Refuses one offer: it leaves this villager's set, and only that slot is refilled.
     *
     * <p>Returns whether anything actually changed, so the caller can tell a genuine refusal from a
     * client sending an id that was never on the menu.
     */
    public static boolean decline(ServerPlayer player, Entity villager, PlayerQuestData data,
                                  ResourceLocation questId) {
        ServerLevel level = (ServerLevel) player.level();
        long now = level.getGameTime();
        OfferSession session = data.offers().get(villager.getUUID());
        // Never trust a client-supplied id: you can only decline something you were actually offered.
        int index = session.removeSlot(questId);
        if (index < 0) {
            return false;
        }
        session.decline(questId, now, McaQuestsConfig.COMMON.declineCooldownTicks.get());
        if (McaQuestsConfig.COMMON.declineRefillsSlot.get()) {
            OfferFilters.Pass pass = OfferFilters.Pass.of(player, villager, data);
            drawOne(pass, session, now).ifPresent(slot -> session.slots().add(index, slot));
        }
        return true;
    }

    /** The remembered slot for a quest this villager is offering, if it is one. */
    public static Optional<OfferSession.Slot> slotFor(PlayerQuestData data, UUID villagerUuid,
                                                      ResourceLocation questId) {
        return data.offers().find(villagerUuid).flatMap(session -> session.slots().stream()
                .filter(slot -> slot.questId().equals(questId))
                .findFirst());
    }

    /** Forces the next open to draw a fresh set — {@code /mcaquests debug offers reroll}. */
    public static void forceReroll(PlayerQuestData data, UUID villagerUuid) {
        data.offers().remove(villagerUuid);
    }

    // ---------------------------------------------------------------- drawing

    private static void redraw(OfferFilters.Pass pass, OfferSession session, long now, int refreshTicks) {
        redraw(pass, session, now, refreshTicks, null);
    }

    private static void redraw(OfferFilters.Pass pass, OfferSession session, long now, int refreshTicks,
                               @javax.annotation.Nullable Set<ResourceLocation> allowedQuestIds) {
        redraw(pass, session, now, refreshTicks, allowedQuestIds, null);
    }

    private static void redraw(OfferFilters.Pass pass, OfferSession session, long now, int refreshTicks,
                               @javax.annotation.Nullable Set<ResourceLocation> allowedQuestIds,
                               @javax.annotation.Nullable String binding) {
        // OfferSession#redraw drops the refusals that were only meant to last as long as the old set --
        // turning something down is meant to last until the villager has something new to say, not to ban
        // it. A refusal given an explicit declineCooldownTicks is about the clock rather than about this
        // menu, so it survives.
        long epoch = refreshTicks <= 0 ? 0L : now / refreshTicks;
        long seed = offerSeed(pass.player(), session.villagerUuid(), epoch);
        List<QuestDefinition> pool = offerablePool(pass, session, now, allowedQuestIds, binding);
        List<QuestDefinition> chosen = QuestManager.selectOffers(pass, pool, seed);
        List<OfferSession.Slot> slots = new ArrayList<>();
        for (QuestDefinition def : chosen) {
            freeze(pass, def).ifPresent(slots::add);
        }
        session.redraw(slots, now, QuestRegistry.generation(), seed, allowedQuestIds, binding);
    }

    /**
     * Re-checks the slots already drawn and replaces the ones that have gone bad.
     *
     * <p>This is what keeps a remembered set honest without redrawing it. A quest can stop being offerable
     * between two opens for entirely ordinary reasons — the player accepted it from another villager, its
     * cooldown started, the relative it names died — and a card that would be refused on click is worse
     * than one that quietly changed.
     */
    private static void revalidate(OfferFilters.Pass pass, OfferSession session, long now) {
        List<OfferSession.Slot> slots = session.slots();
        for (int i = 0; i < slots.size(); i++) {
            OfferSession.Slot slot = slots.get(i);
            if (stillOfferable(pass, slot, now, session)) {
                continue;
            }
            Optional<OfferSession.Slot> replacement = drawOne(pass, session, now);
            if (replacement.isPresent()) {
                slots.set(i, replacement.get());
            } else {
                slots.remove(i--);
            }
        }
        // Refill anything the loop could not replace in place. A set that has lost cards — to a situation
        // that closed, to a cooldown that started, to a refusal that could not be refilled at the time —
        // is not a set the villager should keep showing short, and drawOne already excludes the refusals.
        int wanted = McaQuestsConfig.COMMON.offersPerVillager.get();
        while (slots.size() < wanted) {
            Optional<OfferSession.Slot> extra = drawOne(pass, session, now);
            if (extra.isEmpty()) {
                break;
            }
            slots.add(extra.get());
        }
    }

    private static boolean stillOfferable(OfferFilters.Pass pass, OfferSession.Slot slot, long now,
                                          OfferSession session) {
        if (session.isDeclined(slot.questId(), now)) {
            return false;
        }
        return QuestDefinitions.resolve(slot.questId())
                .map(def -> {
                    if (slot.frozenValues() != null && def.template().isPresent()) {
                        Optional<TemplateSpec.Concrete> concrete = def.template().get().toConcrete(slot.frozenValues());
                        if (concrete.isEmpty() || !CapitalsQuestRequirements.allowsOffer(def.withConcrete(concrete.get()))) {
                            return false;
                        }
                    }
                    return OfferFilters.passes(pass, def);
                })
                .orElse(false);
    }

    /** One replacement slot, drawn from what is left after the current slots and the refusals. */
    private static Optional<OfferSession.Slot> drawOne(OfferFilters.Pass pass, OfferSession session, long now) {
        Set<ResourceLocation> taken = new HashSet<>();
        session.slots().forEach(slot -> taken.add(slot.questId()));
        List<QuestDefinition> pool = offerablePool(pass, session, now).stream()
                .filter(def -> !taken.contains(def.id()))
                .toList();
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        // Perturbed by how many slots are filled, so a refusal draws something other than the card that
        // was already sitting next to it, yet the same world state still gives the same replacement.
        long seed = session.seed() + 31L * (session.slots().size() + 1);
        for (QuestDefinition def : WeightedPicker.pickMany(pool, QuestManager.weightFn(pass), seed, pool.size())) {
            Optional<OfferSession.Slot> frozen = freeze(pass, def);
            if (frozen.isPresent()) {
                return frozen;
            }
        }
        return Optional.empty();
    }

    private static List<QuestDefinition> offerablePool(OfferFilters.Pass pass, OfferSession session, long now) {
        return QuestManager.eligibleOffers(pass).stream()
                .filter(def -> def.institutionalCommission() == session.institutionalBinding().isPresent())
                .filter(def -> !session.isDeclined(def.id(), now))
                .filter(def -> session.allowsInCurrentScope(def.id()))
                .toList();
    }

    private static List<QuestDefinition> offerablePool(OfferFilters.Pass pass, OfferSession session, long now,
                                                       @javax.annotation.Nullable Set<ResourceLocation> allowed) {
        return offerablePool(pass, session, now, allowed, null);
    }

    private static List<QuestDefinition> offerablePool(OfferFilters.Pass pass, OfferSession session, long now,
                                                       @javax.annotation.Nullable Set<ResourceLocation> allowed,
                                                       @javax.annotation.Nullable String binding) {
        return QuestManager.eligibleOffers(pass).stream()
                .filter(def -> def.institutionalCommission() == (binding != null))
                .filter(def -> !session.isDeclined(def.id(), now))
                .filter(def -> allowed == null || allowed.contains(def.id()))
                .toList();
    }

    /**
     * Turns a chosen quest into a remembered card: its template values resolved once, and its offer line
     * voiced once, on the server thread.
     *
     * <p>Voicing here rather than per render is not an optimisation, it is the fix. An add-on resolver is
     * arbitrary third-party code behind a {@code volatile} field, and calling it on every render made a
     * villager's offer text change every time the player reopened the menu.
     *
     * <p>Empty when a template's variables cannot be resolved right now (an empty pool, an unparsable
     * substitution) — exactly as the pre-session code skipped such a card.
     */
    private static Optional<OfferSession.Slot> freeze(OfferFilters.Pass pass, QuestDefinition def) {
        ServerPlayer player = pass.player();
        Entity villager = pass.villager();
        ResolvedTemplate values = null;
        QuestDefinition resolved = def;
        PlaceholderResolver resolver = PlaceholderResolver.forPlayer(player);
        if (def.isTemplate()) {
            TemplateSpec spec = def.template().orElseThrow();
            QuestContext context = new QuestContext(player, villager, pass.data(), def.id(), pass.snapshot());
            Optional<ResolvedTemplate> maybe = spec.resolveValues(context);
            Optional<TemplateSpec.Concrete> concrete = maybe.flatMap(spec::toConcrete);
            if (maybe.isEmpty() || concrete.isEmpty()) {
                McaQuests.LOGGER.debug("[MCA: Quests] Skipping template offer '{}' — could not resolve its variables.",
                        def.id());
                return Optional.empty();
            }
            values = maybe.get();
            resolved = def.withConcrete(concrete.get());
            resolver = new PlaceholderResolver(values, McaCompat.getPlayerName(player));
        }
        if (!CapitalsQuestRequirements.allowsOffer(resolved)) {
            return Optional.empty();
        }
        Component fallback = resolved.dialogueOr(QuestDefinition.OFFER, resolved.title(resolver), resolver);
        Component voiced = QuestDialogueHooks.resolve(player, villager, resolved, QuestDefinition.OFFER, fallback);
        return Optional.of(new OfferSession.Slot(def.id(), values, voiced));
    }

    // ---------------------------------------------------------------- rendering

    /**
     * Resolves each remembered slot back into something renderable, dropping any whose definition has
     * vanished. Does no drawing and no dialogue resolution: this runs on every menu open.
     */
    private static List<Offer> resolveAll(OfferFilters.Pass pass, OfferSession session) {
        List<Offer> offers = new ArrayList<>();
        for (OfferSession.Slot slot : session.slots()) {
            QuestDefinitions.resolve(slot.questId()).ifPresent(base -> {
                QuestDefinition def = base;
                PlaceholderResolver resolver = PlaceholderResolver.forPlayer(pass.player());
                if (slot.frozenValues() != null && base.template().isPresent()) {
                    Optional<TemplateSpec.Concrete> concrete =
                            base.template().get().toConcrete(slot.frozenValues());
                    if (concrete.isEmpty()) {
                        return; // the template changed shape under the saved values; skip this card
                    }
                    def = base.withConcrete(concrete.get());
                    resolver = new PlaceholderResolver(slot.frozenValues(),
                            McaCompat.getPlayerName(pass.player()));
                }
                offers.add(new Offer(slot, def, resolver));
            });
        }
        return offers;
    }

    /**
     * The draw seed. Same shape it always had, with the session epoch in place of the world day — so a
     * fresh draw behaves exactly as it used to, and {@code offerRefreshTicks} genuinely controls how often
     * one happens.
     */
    static long offerSeed(ServerPlayer player, UUID villagerUuid, long epoch) {
        return player.getUUID().hashCode() * 31L + villagerUuid.hashCode() * 17L + epoch * 1000003L;
    }
}
