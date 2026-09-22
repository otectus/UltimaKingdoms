package com.ultimakingdoms.core;

import com.ultimakingdoms.api.DetectionSource;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.McaCommunityRef;
import com.ultimakingdoms.api.SettlementBounds;
import com.ultimakingdoms.api.SettlementCandidate;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Optional;

@GameTestHolder("ultima_kingdoms")
@PrefixGameTestTemplate(false)
public class ExternalRefLookupGameTests {
    @GameTest(template = "empty")
    public static void mergedMcaReferencesAndAliasesResolveCanonicalSettlement(GameTestHelper helper) {
        KingdomsService service = UltimaKingdomsApi.get(helper.getLevel().getServer());
        var source = service.registerCandidate(helper.getLevel(), candidate(helper, "external-source", 0, 42));
        var target = service.registerCandidate(helper.getLevel(), candidate(helper, "external-target", 64, 84));
        String historicalName = "External History " + source.id();
        service.rename(source.id(), historicalName);
        service.rename(source.id(), "External Source " + source.id());

        service.merge(source.id(), target.id());

        check(service.getSettlementByExternalRef("mca", "minecraft:overworld#42")
                .orElseThrow().id().equals(target.id()), "Source MCA reference did not follow merge");
        check(service.getSettlementForMcaVillage(new ResourceLocation("minecraft:overworld"), 84)
                .orElseThrow().id().equals(target.id()), "Target MCA convenience lookup failed");
        check(service.getSettlementByMcaVillage(new ResourceLocation("minecraft:overworld"), 42)
                .orElseThrow().id().equals(target.id()), "MCA lookup alias did not follow merge");
        check(service.findSettlement(historicalName).orElseThrow().id().equals(target.id()),
                "Historical source alias did not follow merge");
        check(service.getSettlementExternalRefs(target.id()).get("mca").containsAll(java.util.Set.of("minecraft:overworld#42", "minecraft:overworld#84")),
                "Public merged aliases did not expose both provider references");
        helper.succeed();
    }

    private static SettlementCandidate candidate(GameTestHelper helper, String key, int dx, int villageId) {
        BlockPos anchor = helper.absolutePos(new BlockPos(dx, 2, 2));
        String externalRef = new McaCommunityRef(helper.getLevel().dimension().location(), villageId).format();
        return new SettlementCandidate(helper.getLevel().dimension(), anchor, 4, SettlementBounds.around(anchor, 4),
                new ResourceLocation("ultima_kingdoms", "external_ref_test"), key, DetectionSource.EXTERNAL,
                Optional.empty(), Optional.empty(), Map.of(McaCommunityRef.EXTERNAL_REF_NAMESPACE, externalRef),
                Optional.empty());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
