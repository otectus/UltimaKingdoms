package com.ultimakingdoms.data;

import com.ultimakingdoms.UltimaKingdoms;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.kingdom.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.*;
import java.util.*;
import java.util.stream.Collectors;

@GameTestHolder("ultima_kingdoms")
@PrefixGameTestTemplate(false)
public class ClassificationGameTests {
    private static ResourceLocation id(String s){return new ResourceLocation("ultima_kingdoms",s);}
    private static BiomeRule rule(String name,String kingdom,int priority,String selector){
        return new BiomeRule(id(name),id(kingdom),priority,List.of(new BiomeRule.Selector(new ResourceLocation(selector.replace("#","")),selector.startsWith("#"))),List.of(),false);
    }
    private static KingdomResolution resolve(GameTestHelper h,List<BiomeRule> rules){
        var pos=h.absolutePos(new BlockPos(0,2,0));h.getLevel().getChunkAt(pos);
        String coords=pos.getX()+" "+pos.getY()+" "+pos.getZ();var server=h.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(h.getLevel()),"fillbiome "+(pos.getX()-8)+" "+(pos.getY()-4)+" "+(pos.getZ()-8)+" "+(pos.getX()+8)+" "+(pos.getY()+4)+" "+(pos.getZ()+8)+" minecraft:plains");
        var kingdoms=UltimaKingdoms.DEFINITIONS.snapshot().kingdoms().stream().map(k->(KingdomDefinition)k).collect(Collectors.toMap(KingdomDefinition::id,k->k));
        var defs=new DefinitionSnapshot(kingdoms,Map.of(),rules,List.of(),id("serenum"),1);
        return defs.resolve(h.getLevel(),SettlementCandidate.structure(h.getLevel().dimension(),pos,0,id("test"),"rules",null));
    }
    private static void check(boolean value,String msg){if(!value)throw new net.minecraft.gametest.framework.GameTestAssertException(msg);}
    @GameTest(template="empty") public static void overlappingTagsHonorPriorityAndStableRuleId(GameTestHelper h){
        var result=resolve(h,List.of(rule("low","yew",1,"#minecraft:is_overworld"),rule("high","madera",2,"#minecraft:is_overworld")));
        check(result.kingdomId().equals(id("madera")),"Higher priority tag failed");
        var a=rule("a_tie","lunari",2,"#minecraft:is_overworld");var z=rule("z_tie","anemosia",2,"#minecraft:is_overworld");
        check(resolve(h,List.of(z,a)).kingdomId().equals(id("lunari")),"Stable rule-id tie break failed");
        check(resolve(h,List.of(a,z)).kingdomId().equals(id("lunari")),"Tie depends on load order");h.succeed();
    }
    @GameTest(template="empty") public static void exactSelectorPrecedenceAndUnknownFallback(GameTestHelper h){
        var result=resolve(h,List.of(rule("tag","lunari",999,"#minecraft:is_overworld"),rule("exact","serenum",1,"minecraft:plains")));
        check(result.kingdomId().equals(id("serenum")),"Tag displaced exact selector");
        var unknown=resolve(h,List.of(rule("no_match","yew",10,"test:unrelated_biome")));
        check(unknown.kingdomId().equals(id("serenum"))&&unknown.source()==AssignmentSource.FALLBACK,"Unknown biome fallback failed");h.succeed();
    }
    @GameTest(template="empty") public static void datapackAddedBiomeUsesTagClassification(GameTestHelper h){
        var pos=h.absolutePos(new BlockPos(0,2,0));h.getLevel().getChunkAt(pos);String coords=pos.getX()+" "+pos.getY()+" "+pos.getZ();
        var server=h.getLevel().getServer();server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(h.getLevel()),
                "fillbiome "+(pos.getX()-8)+" "+(pos.getY()-4)+" "+(pos.getZ()-8)+" "+(pos.getX()+8)+" "+(pos.getY()+4)+" "+(pos.getZ()+8)+" acceptance:tagged_meadow");
        var result=UltimaKingdoms.DEFINITIONS.snapshot().resolve(h.getLevel(),SettlementCandidate.structure(h.getLevel().dimension(),pos,0,id("test"),"modded",null));
        check(result.kingdomId().equals(id("madera"))&&result.source()==AssignmentSource.BIOME_TAG,"Datapack-added biome did not use kingdom tag: "+result);h.succeed();
    }

}
