package com.ultimakingdoms.evolution;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.ultimakingdoms.evolution.EvolutionState.*;
import static org.junit.jupiter.api.Assertions.*;

class EvolutionStateTest {
    private Scenario scenario() {
        return new Scenario(UUID.randomUUID(), "ultima:test", new Template(1, "Aid", Trigger.DEMAND,
                List.of(Outcome.AID, Outcome.MEDIATE, Outcome.DECLINE), 1200, 2400, 1, false), UUID.randomUUID(),
                null, "ultima:a", new Evidence("owner", "receipt", "DEMAND", 10, "Verified demand"), null,
                10, 1210, 1, Phase.OPEN, Map.of(), null, "");
    }
    @Test void opposingChoicesHaveOneTerminalResolution() {
        var first = UUID.randomUUID(); var second = UUID.randomUUID(); var s = scenario();
        s = s.contribute(new Contribution(first, Outcome.AID, new Evidence("quests", "a", "RELIEF", 20, "Aid")), 1, 20);
        s = s.contribute(new Contribution(second, Outcome.MEDIATE, new Evidence("quests", "b", "MEDIATION", 20, "Mediation")), 2, 20);
        var pending = s.reserve(new Intent(first, Outcome.AID, UUID.randomUUID(), 4, ""), 3, 30);
        assertThrows(IllegalArgumentException.class, () -> pending.reserve(new Intent(second, Outcome.MEDIATE, UUID.randomUUID(), 4, ""), 3, 30));
        var done = pending.finish("owner-receipt");
        assertEquals(Outcome.AID, done.intent().outcome()); assertEquals(Phase.RESOLVED, done.phase());
        assertThrows(IllegalArgumentException.class, () -> done.finish("other-receipt"));
    }
    @Test void withdrawalAndNeutralDeclinePreserveContributionProvenance() {
        UUID player = UUID.randomUUID(); var e = scenario();
        var contributed = e.contribute(new Contribution(player, Outcome.AID, e.cause()), 1, 20);
        var withdrawn = contributed.withdraw(player, 2, 21);
        assertTrue(withdrawn.contributions().isEmpty());
        var declined = withdrawn.reserve(new Intent(player, Outcome.DECLINE, UUID.randomUUID(), 0, ""), 3, 22).finish("Declined");
        assertEquals(Phase.DECLINED, declined.phase());
        assertThrows(IllegalArgumentException.class, () -> e.reserve(new Intent(player, Outcome.AID, UUID.randomUUID(), 0, ""), 1, 22));
    }
    @Test void pendingResolutionSurvivesDeadlineAndRestartWithoutReroll() {
        var e = scenario(); var player = UUID.randomUUID();
        e = e.contribute(new Contribution(player, Outcome.AID, e.cause()), 1, 20)
                .reserve(new Intent(player, Outcome.AID, UUID.randomUUID(), 9, ""), 2, 21);
        var s = new EvolutionState(); s.scenarios.put(e.id(), e); s.enabled = true;
        var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", EvolutionSavedData.JSON.toJson(s));
        var data = EvolutionSavedData.load(tag); assertTrue(data.writable());
        var restored = data.snapshot().scenarios.get(e.id()); assertEquals(e, restored);
        assertEquals(restored, restored.expire(2000));
        assertEquals(Phase.EXPIRED, scenario().expire(2000).phase());
    }
    @Test void unknownAndMalformedSavesStayReadOnlyAndIntact() {
        for (String payload : List.of("{}", "null", "{\"revision\":0}", "not-json")) {
            var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", payload);
            var data = EvolutionSavedData.load(tag); assertFalse(data.writable()); assertEquals(tag, data.save(new CompoundTag()));
        }
        var tag = new CompoundTag(); tag.putInt("Schema", 7); tag.putString("Payload", "future");
        assertEquals(tag, EvolutionSavedData.load(tag).save(new CompoundTag()));
    }
    @Test void invalidTermsCannotRemoveNeutralExitOrExceedBudget() {
        assertThrows(IllegalArgumentException.class, () -> new Template(1, "Bad", Trigger.GRIEVANCE, List.of(Outcome.AID, Outcome.MEDIATE), 1200, 1200, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Template(1, "Bad", Trigger.GRIEVANCE, List.of(Outcome.AID, Outcome.DECLINE), 1200, 1200, 65, true));
    }
    @Test void inactiveTimeExtendsOnlyOpenDeadlinesAndRetainsEvidence() {
        var original=scenario();var paused=original.pause(24000);
        assertEquals(original.deadline()+24000,paused.deadline());
        assertEquals(original.cause(),paused.cause());
        assertEquals(Phase.OPEN,paused.expire(24010).phase());
        var done=original.reserve(new Intent(UUID.randomUUID(),Outcome.DECLINE,UUID.randomUUID(),0,""),1,20).finish("Declined");
        assertEquals(done,done.pause(24000));
    }
}
