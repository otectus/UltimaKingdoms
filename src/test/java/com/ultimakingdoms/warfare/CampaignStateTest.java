package com.ultimakingdoms.warfare;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.ultimakingdoms.warfare.CampaignState.*;
import static org.junit.jupiter.api.Assertions.*;

class CampaignStateTest {
    private Accord proposed() {
        return new Accord(UUID.randomUUID(), UUID.randomUUID(), "a", "b", "ultima:a", "ultima:b",
                Relation.NEUTRAL, Relation.ENEMY, Relation.ENEMY, Map.of("a", UUID.randomUUID()),
                TreatyPhase.PROPOSED, Sovereignty.AUTONOMOUS, "ultima:a", 1000, 3, UUID.randomUUID(), "b", "Relief and autonomy");
    }
    @Test void signaturesAreFrozenAndCannotImpersonateBothSides() {
        var p = proposed();
        assertThrows(IllegalArgumentException.class, () -> p.sign("b", p.signatures().get("a")));
        var signed = p.sign("b", UUID.randomUUID());
        assertEquals(TreatyPhase.SIGNED, signed.phase());
        assertEquals(1, p.signatures().size());
        assertThrows(UnsupportedOperationException.class, () -> signed.signatures().clear());
        assertEquals(p.claim(), signed.claim());
    }
    @Test void SovereigntyRequiresMatchingAcknowledgedAccord() {
        var a = proposed().sign("b", UUID.randomUUID()); var s = new CampaignState();
        s.accords.put(a.id(), a);
        s.decisions.put(a.settlement(), new Decision(a.settlement(), a.id(), a.sovereignty(), a.recognizedKingdom(), 20, a.controlSequence()));
        assertThrows(IllegalArgumentException.class, s::validate);
        s.accords.put(a.id(), a.phase(TreatyPhase.ACTIVE, "acknowledged")); s.validate();
        s.accords.put(a.id(), a.phase(TreatyPhase.BREACHED, "native change")); s.validate();
        s.decisions.put(a.settlement(), new Decision(a.settlement(), a.id(), Sovereignty.INDEPENDENT, a.recognizedKingdom(), 20, a.controlSequence()));
        assertThrows(IllegalArgumentException.class, s::validate);
    }
    @Test void futureMalformedAndTruncatedPayloadsRemainPreserved() {
        for (var payload : List.of("{}", "null", "{\"revision\":0}", "not json")) {
            var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", payload);
            var data = CampaignSavedData.load(tag); assertFalse(data.writable()); assertEquals(tag, data.save(new CompoundTag()));
        }
        var future = new CompoundTag(); future.putInt("Schema", 99); future.putString("Payload", "opaque");
        assertEquals(future, CampaignSavedData.load(future).save(new CompoundTag()));
    }
    @Test void roundtripKeepsPendingIntentWithoutInventingDecision() {
        var s = new CampaignState(); var a = proposed().sign("b", UUID.randomUUID()).phase(TreatyPhase.PENDING, "native partial write");
        s.accords.put(a.id(), a); var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", CampaignSavedData.JSON.toJson(s));
        var data = CampaignSavedData.load(tag); assertTrue(data.writable());
        assertEquals(a, data.snapshot().accords.get(a.id())); assertTrue(data.snapshot().decisions.isEmpty());
    }
}
