package dev.otectus.mcaconversations.compat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CivicBridgeTest {
    private static final UUID NPC = UUID.fromString("a0f63bf6-dd4f-4eb3-97ab-1e204fd733b8");

    public record FakeContext(UUID npc, String organization, String nameKey, String role,
                              boolean servicesAvailable, boolean introductionQualified,
                              boolean commissionQualified, List<String> reasons,
                              List<String> commissionReasons, long stateRevision, long policyRevision) {
    }

    public static final class FakeResult {
        public boolean success() { return true; }
        public String reason() { return "civic.introduction_sent"; }
        public Optional<UUID> settlement() {
            throw new AssertionError("the conversations bridge must never read hidden destinations");
        }
    }

    @Test
    void publicContextIsFlattenedAndBounded() throws Throwable {
        CivicBridge.Contact context = CivicBridge.decodeContextForTest(new FakeContext(
                NPC, "ultima_kingdoms:civic/lamplighters", "civic.organization/lamplighters",
                "guild_contact", true, true, false,
                List.of("civic.qualified"), List.of("civic.rank_required"), 14L, 9L));

        assertEquals(NPC, context.npc());
        assertEquals("ultima_kingdoms:civic/lamplighters", context.organization().toString());
        assertEquals(List.of("civic.rank_required"), context.commissionReasons());
        assertThrows(UnsupportedOperationException.class,
                () -> context.reasons().add("civic.not_allowed"));
    }

    @Test
    void malformedOrOversizedProviderContextFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> CivicBridge.decodeContextForTest(
                new FakeContext(NPC, "NOT A RESOURCE", "civic.name", "contact",
                        true, true, true, List.of(), List.of(), 1L, 1L)));
        assertThrows(IllegalArgumentException.class, () -> CivicBridge.decodeContextForTest(
                new FakeContext(NPC, "ultima_kingdoms:guild", "civic.name", "contact",
                        true, true, true, List.of("x", "x", "x", "x", "x", "x", "x", "x", "x"),
                        List.of(), 1L, 1L)));
    }

    @Test
    void actionReplyNeverTouchesSettlementIdentity() throws Throwable {
        assertEquals(new CivicBridge.Reply(true, "civic.introduction_sent"),
                CivicBridge.decodeReplyForTest(new FakeResult()));
    }
}
