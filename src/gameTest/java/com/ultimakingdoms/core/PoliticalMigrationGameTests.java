package com.ultimakingdoms.core;

import com.ultimakingdoms.politics.PoliticalSavedData;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraftforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@GameTestHolder("ultima_kingdoms")
@PrefixGameTestTemplate(false)
public class PoliticalMigrationGameTests {
    @GameTest(template="empty")
    public static void settlementOnlyFixtureKeepsIdentityWhenPoliticsIsAdded(GameTestHelper helper) throws Exception {
        String snbt;
        try(var input=PoliticalMigrationGameTests.class.getResourceAsStream("/fixtures/settlement-only-schema1.snbt")) {
            if(input==null)throw new GameTestAssertException("Missing legacy fixture");
            snbt=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        CompoundTag original=TagParser.parseTag(snbt);
        var load=SettlementSavedData.class.getDeclaredMethod("load",CompoundTag.class);load.setAccessible(true);
        SettlementSavedData identity=(SettlementSavedData)load.invoke(null,original);
        CompoundTag before=identity.save(new CompoundTag());
        CompoundTag political=new PoliticalSavedData().save(new CompoundTag());
        if(!before.equals(identity.save(new CompoundTag())))throw new GameTestAssertException("Political initialization altered legacy identity");
        var record=identity.get(new UUID(0,42)).orElseThrow();
        if(!record.displayName.equals("Bellmeadow")||!record.aliases.contains("Old Bellmeadow")
                ||!record.externalRefs.get("mca").equals("minecraft:overworld#42")
                ||!political.getString("Records").contains("\"governments\":{}"))throw new GameTestAssertException("Legacy identity or empty political state changed");
        helper.succeed();
    }
}
