package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.api.townstead.*;
import com.ultimakingdoms.politics.PoliticalSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;
import java.nio.file.*;
import java.util.function.Consumer;

/** Packaged-save and exact-provider validation; reflection below is test-fixture setup only. */
final class PoliticalScenario {
    private static final String A="ultima_kingdoms:serenum",B="ultima_kingdoms:lunari";
    private static final UUID ONE=UUID.fromString("52b0bba4-8a95-4b05-a79e-f2b6dccafaa1"),TWO=UUID.fromString("52b0bba4-8a95-4b05-a79e-f2b6dccafaa2");
    static void run(MinecraftServer server,String phase,java.util.function.BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        var politics=UltimaPoliticsApi.get(server);var identity=UltimaKingdomsApi.get(server);
        var one=new FakePlayer(server.overworld(),new GameProfile(ONE,"PoliticsOne")){@Override public boolean hasPermissions(int n){return n<=2;}};
        var two=new FakePlayer(server.overworld(),new GameProfile(TWO,"PoliticsTwo"));
        server.getProfileCache().add(one.getGameProfile());server.getProfileCache().add(two.getGameProfile());
        if(phase.equals("politics-future")){
            var result=politics.execute(one,request(politics,Action.SEAT,A,"",UUID.randomUUID().toString(),null,"","","",BlockPos.ZERO));
            check(!result.success()&&result.message().contains("read-only"),"Future political schema rejects writes");
            check(!identity.getKingdoms().isEmpty(),"Identity remains available with future political data");schedule.accept(80L, () -> finish.accept("PASS integration political future-schema preservation"));return;
        }
        if(phase.equals("politics-restart")){
            check(politics.page(one,UUID.randomUUID(),A,"overview",0).rows().stream().anyMatch(r->r.title().equals("ACTIVE")),"Government survives process restart");
            check(politics.page(one,UUID.randomUUID(),A,"council",0).rows().size()==2,"NPC office survives process restart");
            check(politics.page(two,UUID.randomUUID(),A,"agreements",0).rows().stream().anyMatch(r->r.detail().startsWith("ACTIVE")),"Ratified agreement survives process restart");
            check(politics.page(one,UUID.randomUUID(),A,"honors",0).rows().size()==1,"Petition honor survives process restart");
            if(ModList.get().isLoaded("townstead"))check(politics.page(one,UUID.randomUUID(),A,"institutions",0).rows().size()==1,"Institution survives process restart");
            Request replay=PoliticalSavedData.JSON.fromJson(Files.readString(Path.of("political-replay.json")),Request.class);
            Result result=politics.execute(one,replay);check(result.success()&&politics.page(one,UUID.randomUUID(),A,"honors",0).rows().size()==1,"Persisted request replay returns receipt without duplicate honor");
            schedule.accept(80L, () -> finish.accept("PASS integration political restart, offices, ratified terms and receipt replay"));return;
        }
        BlockPos pos=new BlockPos(4000,-60,4000);server.overworld().getChunkAt(pos);server.overworld().setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);
        var a=identity.registerCandidate(server.overworld(),SettlementCandidate.external(server.overworld().dimension(),pos,32,SettlementBounds.around(pos,32),new ResourceLocation("ultima_acceptance:politics"),"seat-a",new ResourceLocation(A),Map.of("mca","minecraft:overworld#99101"),"Political Bellmeadow"));
        var b=identity.registerCandidate(server.overworld(),SettlementCandidate.external(server.overworld().dimension(),pos.offset(160,0,0),32,SettlementBounds.around(pos.offset(160,0,0),32),new ResourceLocation("ultima_acceptance:politics"),"seat-b",new ResourceLocation(B),Map.of(),"Political Winter Archive"));
        success(politics.execute(one,request(politics,Action.BOOTSTRAP,A,A+"_charter",a.id().toString(),new Person(ONE,Kind.PLAYER),"","","",pos)));
        success(politics.execute(one,request(politics,Action.BOOTSTRAP,B,B+"_charter",b.id().toString(),new Person(TWO,Kind.PLAYER),"","","",pos)));
        Entity npc;
        if(ModList.get().isLoaded("mca")) {
            var factory=com.ultimakingdoms.test.McaGameTests.class.getDeclaredMethod("village",ServerLevel.class,int.class,BlockPos.class);factory.setAccessible(true);
            Object village=factory.invoke(null,server.overworld(),99101,pos);
            var buildings=(Map<Integer,Object>)IntegrationScenario.call(village,"getBuildings");
            Object library=buildings.get(0);IntegrationScenario.call(library,"setId",0);IntegrationScenario.call(library,"setType","library");
            for(String axis:new String[]{"X","Y","Z"})for(int side=0;side<=1;side++){
                var field=library.getClass().getDeclaredField("pos"+side+axis);field.setAccessible(true);int center=axis.equals("X")?pos.getX():axis.equals("Y")?pos.getY():pos.getZ();field.setInt(library,center+(side==0?-2:2));
            }
            npc=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca:male_villager")).create(server.overworld());
            var home=com.ultimakingdoms.test.McaGameTests.class.getDeclaredMethod("home",Entity.class,int.class);home.setAccessible(true);home.invoke(null,npc,99101);
        } else npc=EntityType.VILLAGER.create(server.overworld());
        net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn((Mob)npc,server.overworld(),server.overworld().getCurrentDifficultyAt(pos),MobSpawnType.COMMAND,null,null);
        ((AgeableMob)npc).setAge(0);((VillagerDataHolder)npc).setVillagerData(((VillagerDataHolder)npc).getVillagerData().setProfession(VillagerProfession.LIBRARIAN));
        server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
        server.overworld().setBlock(pos.east(2), net.minecraft.world.level.block.Blocks.LECTERN.defaultBlockState(), 3);
        var bed=net.minecraft.world.level.block.Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING,net.minecraft.core.Direction.SOUTH);
        server.overworld().setBlock(pos.west(2),bed,3);
        server.overworld().setBlock(pos.west(2).south(),bed.setValue(net.minecraft.world.level.block.BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.HEAD),3);
        ((LivingEntity)npc).getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,net.minecraft.core.GlobalPos.of(server.overworld().dimension(),pos.east(2)));
        ((LivingEntity)npc).getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME,net.minecraft.core.GlobalPos.of(server.overworld().dimension(),pos.west(2)));
        npc.moveTo(pos.getX(),pos.getY(),pos.getZ());server.overworld().addFreshEntity(npc);identity.setResidence(npc,a.id());
        Entity candidate = npc;
        schedule.accept(40L, () -> {
            try { complete(server, politics, identity, one, two, a, b, candidate, pos, phase.equals("politics-cycle")
                    ? ignored -> observeCycle(server, politics, one, candidate, schedule, finish) : finish); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
    }
    private static void complete(MinecraftServer server, PoliticalService politics, KingdomsService identity,
            ServerPlayer one, ServerPlayer two, SettlementView a, SettlementView b, Entity npc, BlockPos pos, Consumer<String> finish) throws Exception {
        if(ModList.get().isLoaded("townstead")) {
            var view=UltimaTownsteadApi.get(server).villager(npc).orElseThrow();System.out.println("POLITICAL_TOWNSTEAD stage="+view.lifeStage()+" root="+view.rootId());
        }
        CompoundTag before=npc.saveWithoutId(new CompoundTag());
        success(politics.execute(one,request(politics,Action.APPOINT,A,"ultima_kingdoms:keeper_of_records","",new Person(npc.getUUID(),Kind.NPC),"","","",pos)));
        check(before.equals(npc.saveWithoutId(new CompoundTag())),"Office leaves all native NPC state byte-for-byte unchanged at commit");
        check(identity.getSettlement(a.id()).orElseThrow().id().equals(a.id()),"Capital designation preserves settlement identity");
        if(ModList.get().isLoaded("townstead")) {
            var building=UltimaTownsteadApi.get(server).buildingAt(server.overworld(),pos).orElseThrow(()->new AssertionError("Point query did not resolve fixture"));
            check(building.type().equals("library"),"Exact Townstead point query resolves library");
            success(politics.execute(one,request(politics,Action.RECOGNIZE,A,"ultima_kingdoms:learning","",null,"","","",pos)));
        }
        Result proposal=politics.execute(one,request(politics,Action.PROPOSE,A,"ultima_kingdoms:diplomatic_recognition","",null,B,"Mutual public recognition","",pos));success(proposal);
        String hash=politics.page(one,UUID.randomUUID(),A,"agreements",0).rows().get(0).termsHash();
        success(politics.execute(one,request(politics,Action.SIGN,A,"",proposal.recordId(),null,"","",hash,pos)));
        success(politics.execute(two,request(politics,Action.SIGN,B,"",proposal.recordId(),null,"","",hash,pos)));
        Result petition=politics.execute(two,request(politics,Action.PETITION,A,"ultima_kingdoms:honor_petition",a.id().toString(),new Person(TWO,Kind.PLAYER),"","Civic service","",pos));success(petition);
        Request approve=request(politics,Action.APPROVE,A,"",petition.recordId(),null,"","Discretionary recognition","",pos);success(politics.execute(one,approve));
        Files.writeString(Path.of("political-replay.json"),PoliticalSavedData.JSON.toJson(approve));
        check(politics.page(one,UUID.randomUUID(),A,"honors",0).rows().size()==1,"Petition approval awards exactly one honor");
        server.saveEverything(false,true,true);
        finish.accept("PASS integration political capitals, offices, unchanged NPC state, recognition, agreements and petitions");
    }
    private static void observeCycle(MinecraftServer server,PoliticalService politics,ServerPlayer actor,Entity npc,
            java.util.function.BiConsumer<Long,Runnable> schedule,Consumer<String> finish) {
        long start=server.overworld().getGameTime();Set<String> activities=new TreeSet<>();
        var original=UltimaTownsteadApi.get(server).villager(npc).orElseThrow();
        class Observation implements Runnable {
            public void run(){
                check(npc.isAlive(),"Office holder remains alive during daily simulation");
                var view=UltimaTownsteadApi.get(server).villager(npc).orElseThrow();
                activities.add(view.schedule().currentActivity());
                check(view.professionId().equals(original.professionId()),"Librarian profession remains provider-owned");
                check(view.schedule().shifts().equals(original.schedule().shifts()),"Office leaves configured shifts unchanged");
                check(politics.page(actor,UUID.randomUUID(),A,"council",0).rows().stream().anyMatch(r->r.detail().contains(npc.getUUID().toString())),"Office remains queryable during work/rest simulation");
                long elapsed=server.overworld().getGameTime()-start;
                System.out.println("POLITICAL_CYCLE ticks="+elapsed+" activity="+view.schedule().currentActivity()+" planned="+view.schedule().plannedActivity()+" hunger="+view.needs().hunger()+" fatigue="+view.needs().fatigue());
                if(elapsed>=24000){
                    check(activities.stream().anyMatch(a->a.toLowerCase(Locale.ROOT).contains("work"))&&activities.stream().anyMatch(a->a.toLowerCase(Locale.ROOT).contains("rest")),"Observed both work and rest activities: "+activities);
                    finish.accept("PASS integration full 24000-tick Townstead work/rest cycle, office queries, unchanged profession and configured shifts");
                }else schedule.accept(200L,this);
            }
        }
        schedule.accept(200L,new Observation());
    }
    private static Request request(PoliticalService service,Action action,String kingdom,String definition,String target,Person person,String counterpart,String text,String hash,BlockPos pos){return new Request(UUID.randomUUID(),service.revision(),action,kingdom,definition,target,person,counterpart,text,hash,pos.getX(),pos.getY(),pos.getZ());}
    private static void success(Result result){check(result.success(),result.message());}
    private static void check(boolean good,String message){if(!good)throw new AssertionError(message);System.out.println("PASS politics: "+message);}
}
