package dev.otectus.mcaquests.state;

import dev.otectus.mcaquests.support.TestBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a remembered offer set is due to be drawn again, and when it must not be.
 *
 * <p>The 1.4.3 decline fix was undone by the empty list. A player who turned down all three of a
 * villager's offers left a set with no slots in it, {@code isStale} could not tell that apart from a set
 * that had never been drawn, and the redraw dropped the refusals on its way past — so the next open
 * brought back the very three quests they had just refused. These tests pin the distinction.
 */
class OfferSessionTest {

    private static final UUID VILLAGER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final ResourceLocation ONE = new ResourceLocation("mcaquests", "one");
    private static final ResourceLocation TWO = new ResourceLocation("mcaquests", "two");
    private static final ResourceLocation THREE = new ResourceLocation("mcaquests", "three");

    private static final int REFRESH = 24000;
    private static final int GENERATION = 1;

    static {
        TestBootstrap.ensureBootstrapped();
    }

    private static OfferSession.Slot slot(ResourceLocation id) {
        return new OfferSession.Slot(id, null, Component.literal(id.getPath()));
    }

    /** A three-card set drawn at game time 1000, with every card then declined at the default cooldown. */
    private static OfferSession declinedAll() {
        OfferSession session = new OfferSession(VILLAGER);
        session.redraw(List.of(slot(ONE), slot(TWO), slot(THREE)), 1000L, GENERATION, 42L);
        for (ResourceLocation id : List.of(ONE, TWO, THREE)) {
            session.removeSlot(id);
            session.decline(id, 1000L, 0);
        }
        return session;
    }

    @Test
    @DisplayName("a set emptied by declining every card is not stale, and the refusals still hold")
    void declinedEverythingIsNotStale() {
        OfferSession session = declinedAll();

        assertTrue(session.slots().isEmpty());
        assertTrue(session.hasUntilRefreshRefusals());
        assertFalse(session.isStale(1200L, REFRESH, GENERATION),
                "an exhausted menu must stay exhausted, not redraw the quests just refused");
        assertTrue(session.isDeclined(ONE, 1200L));
    }

    @Test
    @DisplayName("it becomes stale once offerRefreshTicks have elapsed")
    void staleAfterTheRefreshWindow() {
        OfferSession session = declinedAll();

        assertTrue(session.isStale(1000L + REFRESH, REFRESH, GENERATION));
    }

    @Test
    @DisplayName("redrawing clears the until-refresh refusals, so the set is stale again when emptied")
    void redrawClearsUntilRefreshRefusals() {
        OfferSession session = declinedAll();

        session.redraw(List.of(), 1000L + REFRESH, GENERATION, 43L);

        assertFalse(session.hasUntilRefreshRefusals());
        assertFalse(session.isDeclined(ONE, 1000L + REFRESH));
        assertTrue(session.isStale(1000L + REFRESH, REFRESH, GENERATION));
    }

    @Test
    @DisplayName("a set that was never drawn is stale")
    void freshSessionIsStale() {
        assertTrue(new OfferSession(VILLAGER).isStale(0L, REFRESH, GENERATION));
    }

    @Test
    void commissionScopeSurvivesSaveAndOrdinaryScopeDoesNotMatchIt() {
        OfferSession session = new OfferSession(VILLAGER);
        Set<ResourceLocation> allowed = Set.of(ONE, THREE);
        session.redraw(List.of(slot(ONE)), 1000L, GENERATION, 42L, allowed);

        OfferSession restored = OfferSession.load(session.save());
        assertEquals(allowed, restored.restrictedQuestIds().orElseThrow());
        assertTrue(restored.scopeMatches(allowed));
        assertFalse(restored.scopeMatches(null));
        assertTrue(restored.allowsInCurrentScope(ONE));
        assertFalse(restored.allowsInCurrentScope(TWO));
    }

    @Test
    void institutionalBindingSurvivesAndAStaleOwnerCannotReuseTheOffer() {
        String binding = "11111111-2222-3333-4444-555555555555";
        Set<ResourceLocation> allowed = Set.of(ONE);
        OfferSession session = new OfferSession(VILLAGER);
        session.redraw(List.of(slot(ONE)), 1000L, GENERATION, 42L, allowed, binding);

        OfferSession restored = OfferSession.load(session.save());
        assertEquals(binding, restored.institutionalBinding().orElseThrow());
        assertEquals(GENERATION, restored.packGeneration(),
                "acceptance can compare the displayed catalogue generation after restart");
        assertTrue(restored.scopeMatches(allowed, binding));
        assertFalse(restored.scopeMatches(allowed, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        assertFalse(restored.scopeMatches(allowed), "ordinary R1 callers cannot inherit the owner token");
    }

    @Test
    @DisplayName("a decline cooldown longer than the pruning horizon survives pruning")
    void timedRefusalSurvivesPruning() {
        OfferSessions sessions = new OfferSessions();
        OfferSession session = sessions.get(VILLAGER);
        session.redraw(List.of(slot(ONE)), 0L, GENERATION, 42L);
        session.removeSlot(ONE);
        session.decline(ONE, 0L, REFRESH * 20); // an explicit cooldown well past 8 refresh windows

        long now = (long) REFRESH * 9; // past the horizon: the session itself is long untouched
        sessions.prune(now, REFRESH);

        assertEquals(1, sessions.size(), "pruning the session would have cut the cooldown short");
        assertTrue(sessions.find(VILLAGER).orElseThrow().isDeclined(ONE, now));
    }

    @Test
    @DisplayName("a lapsed refusal no longer holds the session open")
    void lapsedRefusalIsPruned() {
        OfferSessions sessions = new OfferSessions();
        OfferSession session = sessions.get(VILLAGER);
        session.redraw(List.of(slot(ONE)), 0L, GENERATION, 42L);
        session.removeSlot(ONE);
        session.decline(ONE, 0L, 100);

        sessions.prune((long) REFRESH * 9, REFRESH);

        assertEquals(0, sessions.size());
    }
}
