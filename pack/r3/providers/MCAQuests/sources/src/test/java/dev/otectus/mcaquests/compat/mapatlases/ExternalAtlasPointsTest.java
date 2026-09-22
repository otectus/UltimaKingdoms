package dev.otectus.mcaquests.compat.mapatlases;

import dev.otectus.mcaquests.compat.WaypointPresentation;
import dev.otectus.mcaquests.compat.WaypointSpec;
import dev.otectus.mcaquests.quest.guidance.GuidanceKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExternalAtlasPointsTest {
    @Test void externalLayerRendersButIsNotOwnedByQuestReconciliation(){
        var store=new AtlasMarkerStore();var quest=point("quest");var external=point("external/ultima/site");store.put(quest);
        store.replaceExternal("ultima_kingdoms",List.of(external));
        assertEquals(List.of("quest"),store.keys().stream().sorted().toList());
        assertEquals(2,store.snapshot().all().size());
        store.remove("quest");assertEquals(List.of(external),store.snapshot().all());
        store.replaceExternal("ultima_kingdoms",List.of());assertTrue(store.snapshot().all().isEmpty());
    }
    private static WaypointSpec point(String key){return new WaypointSpec(key,new BlockPos(1,64,2),Level.OVERWORLD,key, GuidanceKind.STRUCTURE,
            WaypointSpec.Ownership.AUTOMATIC, WaypointPresentation.DEFAULT);}
}
