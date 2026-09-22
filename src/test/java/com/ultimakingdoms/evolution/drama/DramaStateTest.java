package com.ultimakingdoms.evolution.drama;

import com.ultimakingdoms.warfare.CampaignState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DramaStateTest {
    @Test void bilateralConsentIsRevisionCheckedAndUsesDistinctPlayers() {
        var drama = sample(DramaState.Kind.INVASION, false);
        UUID defender = UUID.randomUUID();
        var ready = drama.consent("native:defender", defender, 1);
        assertEquals(DramaState.Phase.READY, ready.phase());
        assertThrows(IllegalArgumentException.class, () -> drama.consent("native:defender", drama.proposer(), 1));
        assertThrows(IllegalArgumentException.class, () -> ready.begin(DramaState.Operation.DECLARATION, 1));
    }

    @Test void offlineTimePausesAndOnlineTimeExpiresWithinBound() {
        var ready = sample(DramaState.Kind.CAMPAIGN, false).consent("native:defender", UUID.randomUUID(), 1);
        var offline = ready.evaluate(5000, false);
        assertEquals(ready.remainingTicks(), offline.remainingTicks());
        assertEquals(DramaState.Phase.READY, offline.phase());
        var online = offline.evaluate(5000 + ready.remainingTicks(), true);
        // One evaluation consumes at most 1200 ticks, preventing an unbounded catch-up after downtime.
        assertEquals(ready.remainingTicks() - 1200, online.remainingTicks());
    }

    @Test void providerAbsenceSuspendsSameDeterministicIntentForRecovery() {
        var ready = sample(DramaState.Kind.REBELLION, true).consent("native:defender", UUID.randomUUID(), 1);
        var pending = ready.begin(DramaState.Operation.DECLARATION, ready.revision());
        var suspended = pending.suspended("provider unavailable");
        var retry = suspended.retry(suspended.revision());
        assertEquals(ready.providerRequest(), retry.providerRequest());
        assertEquals(DramaState.Phase.APPLYING, retry.phase());
    }

    @Test void templatesRequireBoundedScopeAndPeacefulRecovery() {
        assertThrows(IllegalArgumentException.class, () -> template(DramaState.Kind.REBELLION, false, Set.of(DramaState.Outcome.DECLARE)));
        assertThrows(IllegalArgumentException.class, () -> new DramaState.Template("bad", DramaState.Kind.INVASION,
                Set.of(DramaState.Outcome.DECLARE, DramaState.Outcome.EXIT), CampaignState.Goal.TRANSFER, "objective", 100, 2, 1, false, null, null));
        assertDoesNotThrow(() -> template(DramaState.Kind.REBELLION, true, Set.of(DramaState.Outcome.DECLARE, DramaState.Outcome.NEGOTIATE, DramaState.Outcome.EXIT)));
    }

    private static DramaState.Drama sample(DramaState.Kind kind, boolean occupied) {
        UUID id = UUID.randomUUID(), proposer = UUID.randomUUID(); var terms = template(kind, occupied,
                Set.of(DramaState.Outcome.DECLARE, DramaState.Outcome.NEGOTIATE, DramaState.Outcome.EXIT, DramaState.Outcome.RECOVER));
        return new DramaState.Drama(id, "test:template", terms, UUID.randomUUID(), proposer, "native:attacker", "native:defender",
                "test:first", "test:second", "", "", Map.of("native:attacker", proposer), Set.of("native:attacker"),
                List.of(new DramaState.Evidence("warfare_control", occupied ? "OCCUPIED" : "CONTROLLED", UUID.randomUUID().toString(), 2, "saved control")),
                0, 2400, 0, 1, 0, DramaState.Phase.PROPOSED, DramaState.Operation.NONE, DramaState.operationId(id, "provider"), "pending");
    }
    private static DramaState.Template template(DramaState.Kind kind, boolean occupied, Set<DramaState.Outcome> outcomes) {
        return new DramaState.Template("Authored", kind, outcomes, CampaignState.Goal.AUTONOMY, "bounded objective", 2400, 2, 2, occupied, null, null);
    }
}
