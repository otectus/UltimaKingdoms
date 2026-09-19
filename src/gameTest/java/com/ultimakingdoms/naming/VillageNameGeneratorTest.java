package com.ultimakingdoms.naming;

import com.ultimakingdoms.api.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import java.util.*;
import static com.ultimakingdoms.test.TestAssertions.*;

@GameTestHolder("ultima_kingdoms")
@PrefixGameTestTemplate(false)
public class VillageNameGeneratorTest {
    private static final ResourceLocation KINGDOM = new ResourceLocation("ultima_kingdoms", "serenum");
    private static final SettlementCandidate CANDIDATE = SettlementCandidate.manual(Level.OVERWORLD,
            new BlockPos(-31, 64, 33), 16, KINGDOM, "test", null);
    private static NamePool pool(List<String> names, Set<String> reserved, Set<String> blocked) {
        return new NamePool(KINGDOM, names, reserved, blocked,
                List.of(new NameTemplate("{root} Haven", 1)), Map.of("root", List.of("New")), 4);
    }
    @GameTest(template="empty") public void normalizationHandlesAccentsPunctuationAndCase(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        assertEquals("lakesend", VillageNameGenerator.normalize("  LÁKE’s-End! "));
        assertEquals(VillageNameGenerator.normalize("Café"), VillageNameGenerator.normalize("Cafe\u0301"));
    }
    @GameTest(template="empty") public void sameSeedHasSameInitialNameAndSlug(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        NamePool pool = pool(List.of("Aldwick", "Greenford"), Set.of(), Set.of());
        assertEquals(VillageNameGenerator.generate(19, CANDIDATE, KINGDOM, pool, s -> false, s -> false),
                VillageNameGenerator.generate(19, CANDIDATE, KINGDOM, pool, s -> false, s -> false));
    }
    @GameTest(template="empty") public void duplicateCanonicalNameSelectsAnother(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var generated = VillageNameGenerator.generate(19, CANDIDATE, KINGDOM,
                pool(List.of("Aldwick", "Greenford"), Set.of(), Set.of()), "aldwick"::equals, s -> false);
        assertEquals("Greenford", generated.displayName());
    }
    @GameTest(template="empty") public void reservedAndBlacklistedNamesAreRejected(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var generated = VillageNameGenerator.generate(19, CANDIDATE, KINGDOM,
                pool(List.of("Aldwick", "Greenford"), Set.of("aldwick"), Set.of("greenford")), s -> false, s -> false);
        assertEquals("New Haven", generated.displayName());
    }
    @GameTest(template="empty") public void slugCollisionPreservesDisplayNameButChangesMachineIdentity(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var generated = VillageNameGenerator.generate(19, CANDIDATE, KINGDOM,
                pool(List.of("Aldwick"), Set.of(), Set.of()), s -> false,
                s -> s.equals(new ResourceLocation("ultima_kingdoms", "aldwick")));
        assertEquals("Aldwick", generated.displayName());
        assertTrue(generated.slug().getPath().startsWith("aldwick-"));
    }
    @GameTest(template="empty") public void exhaustedTemplatesHaveDeterministicFallback(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var generated = VillageNameGenerator.generate(19, CANDIDATE, KINGDOM,
                pool(List.of("Aldwick"), Set.of("aldwick", "newhaven"), Set.of()), s -> false, s -> false);
        assertEquals(Optional.of("deterministic_fallback"), generated.recipe());
        assertFalse(generated.normalizedKey().isBlank());
    }
}
