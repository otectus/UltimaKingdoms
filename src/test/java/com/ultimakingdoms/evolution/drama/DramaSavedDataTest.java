package com.ultimakingdoms.evolution.drama;

import com.ultimakingdoms.warfare.CampaignState;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DramaSavedDataTest {
    @Test void pendingIntentAndFrozenTermsSurviveRestart() {
        var data = new DramaSavedData(); var state = data.snapshot(); UUID id = UUID.randomUUID(), proposer = UUID.randomUUID();
        var terms = new DramaState.Template("Frontier", DramaState.Kind.INVASION,
                Set.of(DramaState.Outcome.DECLARE, DramaState.Outcome.NEGOTIATE, DramaState.Outcome.EXIT), CampaignState.Goal.TRANSFER,
                "specific objective", 2400, 2, 2, false, null, null);
        var proposal = new DramaState.Drama(id, "test:invasion", terms, UUID.randomUUID(), proposer, "native:a", "native:b", "test:a", "test:b", "", "",
                Map.of("native:a", proposer, "native:b", UUID.randomUUID()), Set.of("native:a", "native:b"), List.of(), 12, 2400, 12, 1,
                0, DramaState.Phase.READY, DramaState.Operation.NONE, DramaState.operationId(id, "provider"), "ready");
        state.dramas.put(id, proposal.begin(DramaState.Operation.DECLARATION, 1)); state.revision = 1;
        var reloaded = DramaSavedData.load(DramaSavedData.encode(state));
        assertTrue(reloaded.writable()); assertEquals(DramaState.Phase.APPLYING, reloaded.snapshot().dramas.get(id).phase());
        assertEquals("specific objective", reloaded.snapshot().dramas.get(id).terms().objective());
    }

    @Test void futureSchemaAndMalformedCurrentDataArePreservedReadOnly() {
        var future = new CompoundTag(); future.putInt("Schema", 2); future.putString("Future", "retained");
        var loaded = DramaSavedData.load(future); assertFalse(loaded.writable()); assertEquals(future, loaded.save(new CompoundTag()));
        var malformed = new CompoundTag(); malformed.putInt("Schema", 1); malformed.putString("Payload", "{}");
        loaded = DramaSavedData.load(malformed); assertFalse(loaded.writable()); assertEquals(malformed, loaded.save(new CompoundTag()));
    }

    @Test void bundledTemplatesAreConcreteAndBounded() throws Exception {
        for (String name : List.of("border_campaign", "occupied_rebellion", "frontier_invasion", "concord_schism")) {
            String json = Files.readString(Path.of("src/main/resources/data/ultima_kingdoms/evolution/drama/" + name + ".json"));
            var raw = DramaSavedData.JSON.fromJson(json, DramaState.Template.class);
            var checked = new DramaState.Template(raw.title(), raw.kind(), raw.outcomes(), raw.goal(), raw.objective(), raw.durationTicks(),
                    raw.maxParticipants(), raw.operationBudget(), raw.requiresOccupation(), raw.organizationTemplate(), raw.organizationName());
            assertTrue(checked.durationTicks() <= 2_419_200); assertTrue(checked.operationBudget() <= 16);
            assertTrue(checked.outcomes().contains(DramaState.Outcome.EXIT) || checked.outcomes().contains(DramaState.Outcome.NEGOTIATE));
        }
    }
}
