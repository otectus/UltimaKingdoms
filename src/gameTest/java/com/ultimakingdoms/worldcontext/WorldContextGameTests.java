package com.ultimakingdoms.worldcontext;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;

@net.minecraftforge.gametest.GameTestHolder("ultima_kingdoms")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class WorldContextGameTests {
    @GameTest(template="empty") public static void neutralResourcesRequireDiscoveredReturnPathAndRespectExclusions(GameTestHelper helper){
        var level=helper.getLevel();var server=level.getServer();var world=new WorldContextService(server);
        var operator=new net.minecraftforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"WorldAuthor")){
            @Override public boolean hasPermissions(int level){return level<=2;}
        };
        var visitor=new net.minecraftforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"WorldVisitor"));
        var stranger=new net.minecraftforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"WorldStranger"));
        var forced=java.util.Set.copyOf(level.getForcedChunks());
        operator.setPos(500000,64,500000);var site=world.authorSite(operator,"resource").orElseThrow();world.discoverAuthoredNearby(operator);
        operator.setPos(500032,64,500000);var back=world.authorSite(operator,"return").orElseThrow();world.discoverAuthoredNearby(operator);
        var wood=new net.minecraft.resources.ResourceLocation("minecraft:oak_log");var diamond=new net.minecraft.resources.ResourceLocation("minecraft:diamond");
        helper.assertTrue(world.authorNeutralResource(operator,site,back,java.util.Set.of(wood,diamond),java.util.Set.of(diamond)),"neutral policy saved");
        visitor.setPos(500000,64,500000);world.discoverAuthoredNearby(visitor);
        helper.assertTrue(!world.requestNeutralResourceAccess(visitor,site,wood).allowed(),"return discovery required");
        visitor.setPos(500032,64,500000);world.discoverAuthoredNearby(visitor);
        helper.assertTrue(world.requestNeutralResourceAccess(visitor,site,wood).allowed(),"neutral discovered access allowed");
        helper.assertTrue(!world.requestNeutralResourceAccess(visitor,site,diamond).allowed(),"excluded commodity denied");
        helper.assertTrue(world.sites(stranger,0,64).stream().noneMatch(s->s.id().equals(site)||s.id().equals(back)),"undiscovered sites private");
        helper.assertTrue(forced.equals(java.util.Set.copyOf(level.getForcedChunks())),"no forced chunks introduced");helper.succeed();
    }
    @GameTest(template="empty") public static void summonedAndTamedMobsCannotBecomeEncounterEvidence(GameTestHelper helper){
        var wolf=EntityType.WOLF.create(helper.getLevel());if(wolf==null)throw new AssertionError("wolf missing");
        wolf.setTame(true);wolf.setOwnerUUID(java.util.UUID.randomUUID());
        if(!WorldContextEvents.excluded(wolf))throw new net.minecraft.gametest.framework.GameTestAssertException("tamed mob accepted as hostile encounter");
        var zombie=EntityType.ZOMBIE.create(helper.getLevel());if(zombie==null)throw new AssertionError("zombie missing");
        // Mobs constructed without a proven native spawn lifecycle have no spawn type and fail closed.
        if(!WorldContextEvents.excluded(zombie))throw new net.minecraft.gametest.framework.GameTestAssertException("unproven/summoned mob accepted");
        helper.succeed();
    }
}
