package com.ultimakingdoms.evolution;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.ultimakingdoms.evolution.ProtectionState.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtectionStateTest {
    private Pact proposal(String first, String second) {
        return new Pact(UUID.randomUUID(), first, second, UUID.randomUUID(), Set.of(Duty.CIVIC_AID), Map.of(),
                1, 100000, 1200, 0, 1, Phase.PROPOSED, "Voluntary aid, local laws and offices retained");
    }
    private Pact active(String first, String second) {
        return proposal(first, second).sign(first, UUID.randomUUID(), 1, 2).sign(second, UUID.randomUUID(), 2, 3);
    }
    @Test void bilateralSignaturesAreDistinctAndFrozen() {
        UUID person = UUID.randomUUID(); var p = proposal("a", "b").sign("a", person, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> p.sign("b", person, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> p.sign("c", UUID.randomUUID(), 2, 3));
        assertThrows(IllegalArgumentException.class, () -> p.sign("b", UUID.randomUUID(), 1, 3));
        var signed = p.sign("b", UUID.randomUUID(), 2, 3); assertTrue(signed.effective(4));
        assertThrows(UnsupportedOperationException.class, () -> signed.signatures().clear());
    }
    @Test void cyclesMultipleProtectorsAndDeepHierarchiesAreRejected() {
        var s = new ProtectionState(); var first = active("a", "b"); var cycle = active("b", "a");
        s.pacts.put(first.id(), first); s.pacts.put(cycle.id(), cycle);
        assertThrows(IllegalArgumentException.class, () -> s.hierarchy(10));
        s.pacts.clear();
        for (int i = 0; i < 5; i++) { var p = active("k" + i, "k" + (i + 1)); s.pacts.put(p.id(), p); }
        assertThrows(IllegalArgumentException.class, () -> s.hierarchy(10));
        s.pacts.clear(); var competing = active("c", "b"); s.pacts.put(first.id(), first); s.pacts.put(competing.id(), competing);
        assertThrows(IllegalArgumentException.class, () -> s.hierarchy(10));
    }
    @Test void peacefulExitEndsOnlyEnumeratedObligationsAfterNotice() {
        var p = active("a", "b"); var exiting = p.exit(3, 100);
        assertTrue(exiting.effective(1299)); assertFalse(exiting.effective(1300));
        assertEquals(Phase.TERMINATED, exiting.refresh(1300).phase());
        assertEquals(p.duties(), exiting.duties()); assertEquals(p.beneficiary(), exiting.beneficiary());
    }
    @Test void obligationCannotInventDutyOrReplayTerminalCompletion() {
        var p = active("a", "b"); var s = new ProtectionState(); s.pacts.put(p.id(), p);
        var o = new Obligation(UUID.randomUUID(), p.id(), Duty.DEFENSE_ASSISTANCE, p.beneficiary(), UUID.randomUUID(),
                10, 200, 1, Status.OPEN, null, "", "Out of scope");
        s.obligations.put(o.id(), o); assertThrows(IllegalArgumentException.class, s::validate);
        var valid = new Obligation(o.id(), p.id(), Duty.CIVIC_AID, p.beneficiary(), o.requestedBy(), 10, 200, 1, Status.OPEN, null, "", "Aid");
        var done = valid.finish(Status.SATISFIED, UUID.randomUUID(), "epoch:receipt", "Native completion", 1);
        assertThrows(IllegalArgumentException.class, () -> done.finish(Status.SATISFIED, UUID.randomUUID(), "again", "", 2));
        s.obligations.put(done.id(), done); s.validate();
    }
    @Test void restartRetainsTermsReceiptsAndUnsupportedPayload() {
        var s = new ProtectionState(); var p = active("a", "b"); s.pacts.put(p.id(), p); s.receipts.add("epoch:receipt");
        var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", ProtectionSavedData.JSON.toJson(s));
        var data = ProtectionSavedData.load(tag); assertTrue(data.writable()); assertEquals(p, data.snapshot().pacts.get(p.id())); assertTrue(data.snapshot().receipts.contains("epoch:receipt"));
        tag.putInt("Schema", 9); data = ProtectionSavedData.load(tag); assertFalse(data.writable()); assertEquals(tag, data.save(new CompoundTag()));
    }
}
