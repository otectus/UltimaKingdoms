package com.ultimakingdoms.warfare;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlStateTest {
    private final Map<String, ControlState.Mapping> mappings = Map.of(
            "defender", new ControlState.Mapping("defender", "ultima_kingdoms:serenum"),
            "attacker", new ControlState.Mapping("attacker", "ultima_kingdoms:lunari"));
    private ControlState.Binding original() {
        return new ControlState.Binding(UUID.randomUUID(), UUID.randomUUID(), 0, 0, "ultima_kingdoms:serenum", "defender",
                ControlState.Condition.CONTROLLED, 1, List.of(new ControlState.Entry(1, 0, "defender", ControlState.Condition.CONTROLLED)));
    }
    @Test void siegeReplayAndRecoveryPreserveIdentityAndOneTransition() {
        var original = original();
        var siege = original.observe("defender", true, true, mappings, 10);
        assertSame(siege, siege.observe("defender", true, true, mappings, 20));
        var occupied = siege.observe("attacker", false, true, mappings, 30);
        assertEquals(ControlState.Condition.OCCUPIED, occupied.condition());
        assertEquals(original.sovereign(), occupied.sovereign());
        assertEquals(original.settlement(), occupied.settlement());
        assertEquals(3, occupied.sequence());
        assertSame(occupied, occupied.observe("attacker", false, true, mappings, 40));
        var recovered = occupied.observe("defender", false, true, mappings, 50);
        assertEquals(ControlState.Condition.CONTROLLED, recovered.condition());
        assertEquals(4, recovered.sequence());
    }
    @Test void unknownOwnerAndRemovalDoNotInventAnnexation() {
        var binding = original();
        var unmapped = binding.observe("unknown", false, true, mappings, 1);
        assertEquals(ControlState.Condition.UNMAPPED, unmapped.condition());
        var removed = unmapped.observe("unknown", false, false, mappings, 2);
        assertEquals(ControlState.Condition.CLAIM_REMOVED, removed.condition());
        assertSame(removed, removed.observe("unknown", false, false, mappings, 3));
        assertEquals(binding.sovereign(), removed.sovereign());
    }
    @Test void boundedHistoryRetainsSequenceAndCurrentState() {
        var binding = original();
        for (int i = 1; i <= 100; i++) binding = binding.observe(i % 2 == 0 ? "defender" : "attacker", false, true, mappings, i);
        assertEquals(101, binding.sequence());
        assertEquals(ControlState.HISTORY_LIMIT, binding.history().size());
        assertEquals(70, binding.history().get(0).sequence());
        assertThrows(UnsupportedOperationException.class, () -> original().history().clear());
    }
    @Test void duplicateClaimAndCorruptHistoryAreRejected() {
        var binding = original(); var state = new ControlState();
        state.bindings.put(binding.settlement(), binding);
        var second = new ControlState.Binding(UUID.randomUUID(), binding.claim(), 1, 1, binding.sovereign(), binding.owner(),
                binding.condition(), binding.sequence(), binding.history());
        state.bindings.put(second.settlement(), second);
        assertThrows(IllegalArgumentException.class, state::validate);
        assertThrows(IllegalArgumentException.class, () -> new ControlState.Binding(binding.settlement(), binding.claim(), 0, 0,
                binding.sovereign(), "attacker", binding.condition(), 1, binding.history()));
    }
    @Test void retirementPreservesIdentityHistoryAndAuthority() {
        var original = original(); var operator = UUID.randomUUID();
        var retired = original.retire(10, operator);
        assertEquals(original.claim(), retired.claim());
        assertEquals(original.history().get(0), retired.history().get(0));
        assertEquals(operator, retired.history().get(1).administrator());
        var state = new ControlState(); state.retired.put(retired.claim(), retired); state.validate();
        state.bindings.put(original.settlement(), original);
        assertThrows(IllegalArgumentException.class, state::validate);
    }
}
