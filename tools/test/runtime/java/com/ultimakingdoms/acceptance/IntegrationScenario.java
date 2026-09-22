package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.factions.*;
import com.ultimakingdoms.api.gating.*;
import com.ultimakingdoms.api.townstead.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import com.ultimakingdoms.factions.FactionServiceImpl;
import com.ultimakingdoms.factions.config.FactionConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;
import java.util.*;
import java.nio.file.Files;
import net.minecraft.world.level.storage.LevelResource;
import java.util.function.*;

/** Packaged-runtime regression scenarios; never included in release jars. */
final class IntegrationScenario {
    static final UUID PLAYER=UUID.fromString("721be7a7-110e-4a66-a24b-3ad63d3c92f1");
    static final UUID RECEIPT=UUID.fromString("721be7a7-110e-4a66-a24b-3ad63d3c92f2");
    static final ResourceLocation A=id("ultima_kingdoms:serenum"),B=id("ultima_kingdoms:lunari"),CONSUMER=id("ultima_kingdoms:factions");
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("PASS integration: "+message);}
    static ResourceLocation id(String s){return new ResourceLocation(s);}
    static Object call(Object target,String name,Object...args)throws Exception {
        for(var method:target.getClass().getMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length)return method.invoke(target,args);
        throw new NoSuchMethodException(name);
    }
    static Object api(String method,Object...args)throws Exception {
        for(var m:Class.forName("dev.otectus.mcareputation.api.McaReputationApi").getMethods())if(m.getName().equals(method)&&m.getParameterCount()==args.length)return m.invoke(null,args);
        throw new NoSuchMethodException(method);
    }
    static Object outbox(MinecraftServer server)throws Exception {
        Object data=Class.forName("dev.otectus.mcareputation.state.ReputationSavedData").getMethod("get",MinecraftServer.class).invoke(null,server);
        return call(data,"standingOutbox");
    }
    static long acknowledged(MinecraftServer server)throws Exception {
        Object registration=((Optional<?>)call(outbox(server),"registration",CONSUMER)).orElseThrow();
        return ((Number)call(registration,"acknowledgedThrough")).longValue();
    }
    static int score(FactionServiceImpl factions,ResourceLocation kingdom){return factions.getStanding(PLAYER,kingdom).map(FactionStandingSnapshot::score).orElse(0);}
    static FactionStandingRequest request(){return new FactionStandingRequest(PLAYER,A,7,id("ultima_acceptance:manual"),FactionChangeCause.FACTION_DEED,RECEIPT,1,Optional.empty(),Optional.of("runtime acceptance"),true);}
    @SuppressWarnings("unchecked")
    static void townstead(MinecraftServer server,SettlementView settlement,ServerPlayer player,Entity giver,BlockPos pos)throws Exception {
        var factory=com.ultimakingdoms.test.McaGameTests.class.getDeclaredMethod("village",ServerLevel.class,int.class,BlockPos.class);factory.setAccessible(true);
        Object village=factory.invoke(null,server.overworld(),90101,pos);
        var buildings=(Map<Integer,Object>)call(village,"getBuildings");
        for(var entry:buildings.entrySet()){call(entry.getValue(),"setId",entry.getKey());call(entry.getValue(),"setType","house");}
        var town=UltimaTownsteadApi.get(server);
        check(town.capabilities().contains(TownsteadCapability.READ_BUILDINGS),"installed Townstead exposes building capability");
        check(town.calendar().isPresent(),"installed Townstead public calendar returns a snapshot");
        var views=town.buildings(server.overworld(),settlement.id());
        check(views.size()==8,"Townstead loaded-village buildings map through indexed settlement");
        var bound=town.bind(views.stream().filter(v->v.buildingId()==0).findFirst().orElseThrow());
        Object qSpec=null,qBound=null;
        if(ModList.get().isLoaded("mcaquests")){
            Class<?> spec=Class.forName("dev.otectus.mcaquests.quest.kingdom.CivicBuildingSpec");
            Class<?> target=Class.forName(spec.getName()+"$Target"),recovery=Class.forName(spec.getName()+"$Recovery");
            qSpec=spec.getConstructor(target,recovery,Optional.class).newInstance(target.getEnumConstants()[0],recovery.getEnumConstants()[1],Optional.empty());
            var bridge=Class.forName("dev.otectus.mcaquests.compat.KingdomIntegration");
            qBound=((Optional<?>)bridge.getMethod("bindBuilding",spec,ServerPlayer.class,Entity.class).invoke(null,qSpec,player,giver)).orElseThrow();
            check(qBound!=null,"native Quests bridge binds actual Townstead public building snapshot");
        }
        buildings.remove(0);
        check(town.recover(server.overworld(),bound,BuildingRecoveryPolicy.WAIT).status()==BuildingRecoveryStatus.WAITING,"removed Townstead building waits under WAIT policy");
        check(town.recover(server.overworld(),bound,BuildingRecoveryPolicy.FAIL_WITH_REASON).status()==BuildingRecoveryStatus.FAILED,"removed Townstead building fails with reason under strict policy");
        var rebound=town.recover(server.overworld(),bound,BuildingRecoveryPolicy.REBIND_SAME_FAMILY);
        check(rebound.status()==BuildingRecoveryStatus.REBOUND&&rebound.binding().orElseThrow().buildingId()==1,"removed building deterministically rebinds same family");
        if(qBound!=null){
            var bridge=Class.forName("dev.otectus.mcaquests.compat.KingdomIntegration");
            Object recovered=bridge.getMethod("recoverBuilding",qBound.getClass(),qSpec.getClass(),ServerPlayer.class).invoke(null,qBound,qSpec,player);
            check(call(recovered,"status").toString().equals("REBOUND"),"native Quests bridge recovers through public Townstead interface");
        }
        if(System.getProperty("ultima.acceptance.integration").equals("reactions")){
            var type=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(id("mca:male_villager"));
            var villager=(net.minecraft.world.entity.LivingEntity)type.create(server.overworld());
            villager.moveTo(pos.getX(),pos.getY(),pos.getZ());server.overworld().addFreshEntity(villager);
            for(String reaction:List.of("standing_improved","standing_worsened","settlement_allegiance_changed")){
                var tags=Set.of(reaction.startsWith("standing")?"context:faction":"context:settlement");
                check(town.fireReaction(server.overworld(),villager,Optional.of(player),id("ultima_kingdoms:"+reaction),tags),
                        "installed Townstead dispatches bundled political reaction "+reaction);
            }
            villager.discard();
        }
    }

    static void reactionEvents(MinecraftServer server,SettlementView settlement,BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        var level=server.overworld();var pos=settlement.anchor();level.getChunkAt(pos);level.setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);
        var type=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(id("mca:male_villager"));
        var villager=(net.minecraft.world.entity.LivingEntity)type.create(level);villager.moveTo(pos.getX(),pos.getY(),pos.getZ());level.addFreshEntity(villager);
        var kingdoms=UltimaKingdomsApi.get(server);kingdoms.setResidence(villager,settlement.id());
        schedule.accept(15L,()->{try{
            check(level.getEntity(villager.getUUID())==villager,"political reaction target is a loaded MCA resident");
            kingdoms.setKingdom(settlement.id(),B);
            var tracker=Class.forName("com.aetherianartificer.townstead.reaction.ReactionCooldownTracker");
            boolean ready=(boolean)tracker.getMethod("canClaim",net.minecraft.world.entity.LivingEntity.class,String.class,int.class,long.class).invoke(null,villager,"ultima_kingdoms:settlement_allegiance_changed",400,level.getGameTime());
            check(!ready,"actual settlement ownership event dispatches Townstead reaction and records cooldown");
            finish.accept("PASS integration actual political event and bundled Townstead reaction dispatch");
        }catch(Exception e){throw new RuntimeException(e);}});
    }

    static void migration(MinecraftServer server,FactionServiceImpl factions,String phase,Consumer<String> finish)throws Exception {
        if(phase.equals("migration-restart")){
            check(score(factions,A)==200,"imported mean baseline persists through actual server restart");
            long revision=factions.revision();
            check(factions.previewLegacyMigration().alreadyImported()&&factions.importLegacyMigration().alreadyImported()&&factions.revision()==revision,"persisted migration marker prevents repeat import after restart");
            finish.accept("PASS integration baseline migration restart and idempotency");return;
        }
        var kingdoms=UltimaKingdomsApi.get(server);
        var setter=Arrays.stream(Class.forName("dev.otectus.mcareputation.reputation.ReputationService").getMethods()).filter(m->m.getName().equals("setScore")).findFirst().orElseThrow();
        for(int i=0;i<2;i++){
            var pos=new BlockPos(5000+i*100,64,5000);
            kingdoms.registerCandidate(server.overworld(),SettlementCandidate.external(server.overworld().dimension(),pos,32,SettlementBounds.around(pos,32),id("ultima_acceptance:migration"),"migration-"+i,A,Map.of("mca","minecraft:overworld#"+(90101+i)),"Migration Village "+i));
            setter.invoke(null,server,PLAYER,community(90101+i),100+i*200,id("ultima_acceptance:migration_admin"),server.overworld().getGameTime());
        }
        api("flushStandingChanges",server);var directory=server.getWorldPath(LevelResource.ROOT).resolve("data");
        byte[] source=Files.readAllBytes(directory.resolve("mcareputation.dat")),target=Files.readAllBytes(directory.resolve("ultima_kingdoms_factions.dat"));
        long revision=factions.revision();var report=factions.previewLegacyMigration();
        check(report.importable()&&report.entries().size()==1&&report.entries().get(0).baseline()==200,"real local standing migration previews mean without multiplying by village count");
        check(factions.revision()==revision&&factions.getStanding(PLAYER,A).isEmpty()&&Arrays.equals(source,Files.readAllBytes(directory.resolve("mcareputation.dat")))&&Arrays.equals(target,Files.readAllBytes(directory.resolve("ultima_kingdoms_factions.dat"))),"migration dry-run leaves source and target files byte-identical and creates no standing");
        check(factions.importLegacyMigration().alreadyImported()&&score(factions,A)==200,"explicit baseline import commits expected standing");
        factions.flushDurable();server.saveEverything(false,true,true);finish.accept("PASS integration real local baseline preview and import");
    }
    static void loops(MinecraftServer server,FactionServiceImpl factions,BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        FactionConfig.SYNC_MODE.set(FactionConfig.SyncMode.BIDIRECTIONAL_SEMANTIC);
        int before=score(factions,A);var community=new McaCommunityRef(id("minecraft:overworld"),90101);List<UUID> players=new ArrayList<>();
        for(int p=0;p<20;p++){
            UUID player=UUID.nameUUIDFromBytes(("runtime-loop-player-"+p).getBytes(java.nio.charset.StandardCharsets.UTF_8));players.add(player);
            for(int i=0;i<50;i++){
                UUID operation=UUID.nameUUIDFromBytes(("runtime-loop-"+p+"-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8));int delta=i%2==0?2:-2;
                var result=factions.deliverLocalEffect(player,community,delta,operation,p*50+i+1L,"loop suppression regression");
                if(result!=LocalStandingEffectResult.APPLIED)throw new AssertionError("reverse effect "+p+"/"+i+": "+result);
                if(factions.deliverLocalEffect(player,community,delta,operation,p*50+i+1L,"replay")!=LocalStandingEffectResult.DUPLICATE)throw new AssertionError("duplicate reverse effect "+p+"/"+i);
            }
        }
        schedule.accept(100L,()->{
            check(players.stream().allMatch(player->factions.getLocalStanding(player,community).orElseThrow()==0),"1000 alternating semantic local effects plus 1000 receipt replays have exact expected local scores");
            check(score(factions,A)==before&&players.stream().allMatch(player->factions.getStanding(player,A).isEmpty()),"1000 reverse semantic events create no faction standing and do not amplify back");
            check(factions.diagnosticSummary().contains("pending=0"),"loop-suppressed events leave no blocking pending queue");
            System.out.println("Loop diagnostics: "+factions.diagnosticSummary());
            finish.accept("PASS integration 1000-event semantic loop and replay stress");
        });
    }

    static void named(MinecraftServer server,ServerPlayer player,Entity giver,Consumer<String> finish)throws Exception {
        var gate=id("ultima_acceptance:named");
        check(KingdomGateApi.testNamed(player,giver,gate,Optional.empty()),"startup named gate loaded through datapack registry");
        check(!KingdomGateApi.testNamed(player,giver,id("ultima_acceptance:missing"),Optional.empty()),"unknown named gate fails closed");
        var file=server.getWorldPath(LevelResource.ROOT).resolve("datapacks/integration/data/ultima_acceptance/ultima_kingdoms/kingdom_gates/named.json");
        Files.writeString(file,"{\"subject\":\"giver_residence\",\"include\":[\"ultima_kingdoms:lunari\"]}");
        server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((ignored,error)->server.execute(()->{
            try {
                if(error!=null)throw new RuntimeException(error);
                check(!KingdomGateApi.testNamed(player,giver,gate,Optional.empty()),"successful reload replaces named gate for active context");
                Files.writeString(file,"{ broken json");
                server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((ignored2,error2)->server.execute(()->{
                    try {
                        check(error2!=null,"malformed named gate rejects reload");
                        check(!KingdomGateApi.testNamed(player,giver,gate,Optional.empty()),"failed named-gate reload preserves previous committed predicate");
                        finish.accept("PASS integration transactional named-gate reload");
                    }catch(Throwable failure){failure.printStackTrace();finish.accept("FAIL "+failure);}
                }));
            }catch(Throwable failure){failure.printStackTrace();finish.accept("FAIL "+failure);}
        }));
    }

    static void shadow(MinecraftServer server,FactionServiceImpl factions,BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        check(FactionConfig.SYNC_MODE.get()==FactionConfig.SyncMode.SHADOW,"restart restores explicitly configured SHADOW mode");
        int before=score(factions,B);long ack=acknowledged(server);Object mapped=community(90101);
        api("deliverStandingEffect",server,PLAYER,mapped,8,id("ultima_acceptance:shadow_restart"),"shadow-restart",1L,"restart shadow projection");
        schedule.accept(15L,()->{try{
            check(score(factions,B)==before&&acknowledged(server)==ack&&factions.diagnosticSummary().contains("shadow=2"),"SHADOW restart resumes after trimmed live cursor without GAP or mutation");
            FactionConfig.SYNC_MODE.set(FactionConfig.SyncMode.MCA_TO_FACTION);
        }catch(Exception e){throw new RuntimeException(e);}});
        schedule.accept(30L,()->{try{
            check(score(factions,B)==before+4,"return to LIVE consumes shadow-observed event exactly once");
            FactionConfig.SYNC_MODE.set(FactionConfig.SyncMode.SHADOW);
            api("deliverStandingEffect",server,PLAYER,mapped,6,id("ultima_acceptance:shadow_switch"),"shadow-switch",1L,"in-process shadow projection");
        }catch(Exception e){throw new RuntimeException(e);}});
        schedule.accept(50L,()->{
            check(score(factions,B)==before+4&&factions.diagnosticSummary().contains("shadow=3"),"LIVE to SHADOW transition resets observer cursor after trimming");
            finish.accept("PASS integration SHADOW restart and mode transitions");
        });
    }

    static Object community(int village)throws Exception{return Class.forName("dev.otectus.mcareputation.community.CommunityKey").getConstructor(ResourceLocation.class,int.class).newInstance(id("minecraft:overworld"),village);}
    static void regressions(MinecraftServer server,FactionServiceImpl factions,BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        FactionConfig.SYNC_MODE.set(FactionConfig.SyncMode.MCA_TO_FACTION);
        FactionConfig.MISSING_MAPPING_CAPACITY.set(2);
        Object missing=community(90202),mapped=community(90101);
        long start=factions.durableSourceCursor(CONSUMER.toString()).orElseThrow().through();
        api("deliverStandingEffect",server,PLAYER,missing,8,id("ultima_acceptance:missing"),"unmapped-first",1L,"missing before mapped");
        api("deliverStandingEffect",server,PLAYER,mapped,4,id("ultima_acceptance:later"),"mapped-later",1L,"later mapped");
        api("flushStandingChanges",server);
        var epoch=factions.durableSourceCursor(CONSUMER.toString()).orElseThrow().epoch();
        Object batch=api("pollStandingChanges",server,CONSUMER,epoch,start,64);
        Object envelope=call(((List<?>)call(batch,"deliveries")).get(0),"envelope");
        UUID pending=(UUID)call(envelope,"eventId");
        schedule.accept(15L,()->{try{
            check(factions.diagnosticSummary().contains("unmapped=1"),"unmapped event retained while later mapped event delivered");
            int before=score(factions,A);
            var resolved=factions.resolvePending(pending,A).orElseThrow();
            check(resolved.applied()&&score(factions,A)==before+4,"operator resolves earlier unmapped event after later source checkpoint");
            check(factions.resolvePending(pending,A).isEmpty()&&score(factions,A)==before+4,"resolved custody cannot replay twice");
            var type=Class.forName("dev.otectus.mcareputation.reputation.ReputationService");
            var setter=Arrays.stream(type.getMethods()).filter(m->m.getName().equals("setScore")).findFirst().orElseThrow();
            for(int i=0;i<8;i++)setter.invoke(null,server,PLAYER,mapped,40+i,id("ultima_acceptance:admin"),server.overworld().getGameTime());
            api("deliverStandingEffect",server,PLAYER,mapped,6,id("ultima_acceptance:after_ignored"),"after-ignored",1L,"following ignored events");
        }catch(Exception e){throw new RuntimeException(e);}});
        schedule.accept(35L,()->{
            check(factions.diagnosticSummary().contains("pending=0"),"ignored admin events beyond missing-map capacity do not consume pending slots");
            check(score(factions,B)==5,"mapped event following ignored flood still synchronizes");
            finish.accept("PASS integration unmapped custody and ignored-capacity regressions");
        });
    }

    static void run(MinecraftServer server,BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        var kingdoms=UltimaKingdomsApi.get(server);var factions=(FactionServiceImpl)UltimaFactionsApi.get(server);
        String phase=System.getProperty("ultima.acceptance.integration","initial");
        if(phase.equals("r4")||phase.equals("r4-crash")){schedule.accept(2L,()->{try{EvolutionScenario.run(server,finish);}catch(Throwable failure){failure.printStackTrace();finish.accept("FAIL "+failure);}});return;}
        if(phase.equals("r4-service")){R4ServiceScenario.run(server,schedule,finish);return;}
        if(phase.equals("r4-extensions")){R4PoliticalExtensionsScenario.run(server,finish);return;}
        if(phase.equals("r4-family")){R4FamilyScenario.run(server,finish);return;}
        if(phase.startsWith("r4-")){schedule.accept(100L,()->{try{EvolutionScenario.restart(server,phase,finish);}catch(Throwable failure){failure.printStackTrace();finish.accept("FAIL "+failure);}});return;}
        if(phase.equals("military")){schedule.accept(2L,()->{try{MilitaryScenario.run(server,finish);}catch(Throwable failure){failure.printStackTrace();finish.accept("FAIL "+failure);}});return;}
        if(phase.equals("military-restart")){MilitaryScenario.restart(server,schedule,finish);return;}
        if(phase.startsWith("r3-civilian")){R3CivilianScenario.run(server,phase,schedule,finish);return;}
        if(phase.startsWith("r3")){WarfareScenario.run(server,phase,finish);return;}
        if(phase.startsWith("r2")){R2InstitutionScenario.run(server,phase,schedule,finish);return;}
        if(phase.startsWith("civic-loop")){CivicLoopScenario.run(server,phase,schedule,finish);return;}
        if(phase.startsWith("r1")){CivicScenario.run(server,phase,finish);return;}
        if(phase.startsWith("politics")){PoliticalScenario.run(server,phase,schedule,finish);return;}
        if(phase.equals("migration")||phase.equals("migration-restart")){migration(server,factions,phase,finish);return;}
        if(phase.equals("loops")){loops(server,factions,schedule,finish);return;}
        if(phase.equals("shadow")){shadow(server,factions,schedule,finish);return;}
        if(phase.equals("regressions")){regressions(server,factions,schedule,finish);return;}
        if(phase.equals("restart")){
            check(score(factions,A)==17,"crash restart retains target standing and receipt");
            check(factions.apply(request()).status()==FactionStandingResult.Status.REPLAYED,"durable receipt rejects replay after crash");
            FactionConfig.SYNC_MODE.set(FactionConfig.SyncMode.MCA_TO_FACTION);
            schedule.accept(30L,()->{check(score(factions,A)==17&&score(factions,B)==0,"source replay after crash is exactly once and freezeHistory persists");finish.accept("PASS integration crash restart and replay");});return;
        }
        if(phase.equals("future")){
            check(factions.apply(request()).status()==FactionStandingResult.Status.READ_ONLY,"future faction schema refuses mutation");
            finish.accept("PASS integration future faction schema read-only");return;
        }
        if(ModList.get().isLoaded("mcareputation")) {
            try { Class.forName("dev.otectus.mcareputation.api.StandingConsumer");
                check(factions.durableSourceCursor(CONSUMER.toString()).isPresent(),"fresh source consumer has durable target cursor before first tick");
                check(Files.isRegularFile(server.getWorldPath(LevelResource.ROOT).resolve("data/mcareputation.dat")),"source registration persisted before target initialization completes");
            } catch(ClassNotFoundException old) { }
        }
        BlockPos pos=new BlockPos(4000,64,4000);
        var settlement=kingdoms.registerCandidate(server.overworld(),SettlementCandidate.external(server.overworld().dimension(),pos,32,SettlementBounds.around(pos,32),id("ultima_acceptance:integration"),"integration",A,Map.of("mca","minecraft:overworld#90101"),"Integration Village"));
        check(kingdoms.getSettlementForMcaVillage(id("minecraft:overworld"),90101).orElseThrow().id().equals(settlement.id()),"indexed external reference resolves production settlement");
        var player=FakePlayerFactory.get(server.overworld(),new GameProfile(PLAYER,"IntegrationTest"));
        var giver=EntityType.VILLAGER.create(server.overworld());giver.moveTo(pos.getX(),pos.getY(),pos.getZ());kingdoms.setOrigin(giver,settlement.id());kingdoms.setResidence(giver,settlement.id());
        if(phase.equals("named")){named(server,player,giver,finish);return;}
        if(phase.equals("quests")){NativeQuestScenario.run(server,player,settlement,schedule,finish);return;}
        if(ModList.get().isLoaded("mcaconversations"))NativeConversationScenario.run(server,player,settlement);
        if(ModList.get().isLoaded("townstead"))townstead(server,settlement,player,giver,pos);
        if(phase.equals("reactions")){reactionEvents(server,settlement,schedule,finish);return;}
        String residence="{\"subject\":\"giver_residence\",\"include\":[\"ultima_kingdoms:serenum\"]}";
        check(KingdomGateApi.testJson(player,giver,residence,Optional.empty()),"live residence gate accepts current kingdom");
        var frozen=KingdomGateApi.resolveSnapshot(player,giver,"giver_residence",Optional.empty()).orElseThrow();
        kingdoms.setKingdom(settlement.id(),B);
        check(!KingdomGateApi.testJson(player,giver,residence,Optional.empty())&&KingdomGateApi.testJsonAgainst(residence,frozen),"reassignment changes live gate while frozen acceptance remains valid");
        check(KingdomGateApi.resolveKingdomId(player,giver,"giver_origin",Optional.empty()).orElseThrow().equals(A),"origin remains historical after reassignment");
        kingdoms.setKingdom(settlement.id(),A);
        check(factions.apply(request()).applied()&&score(factions,A)==7,"canonical faction deed applied");
        check(factions.apply(request()).status()==FactionStandingResult.Status.REPLAYED&&score(factions,A)==7,"same receipt cannot double apply");
        long before=factions.revision();factions.previewLegacyMigration();check(factions.revision()==before,"migration dry-run leaves canonical revision unchanged");
        factions.flushDurable();
        if(!ModList.get().isLoaded("mcareputation")){finish.accept("PASS integration standalone civic gates, receipts, and dry-run");return;}
        try{Class.forName("dev.otectus.mcareputation.api.StandingConsumer");}catch(ClassNotFoundException old){finish.accept("PASS integration legacy reputation degrades without durable API");return;}
        check(FactionConfig.SYNC_MODE.get()==FactionConfig.SyncMode.SHADOW,"default reputation bridge mode is SHADOW");
        Object community=Class.forName("dev.otectus.mcareputation.community.CommunityKey").getConstructor(ResourceLocation.class,int.class).newInstance(id("minecraft:overworld"),90101);
        long ackBefore=acknowledged(server);
        Object delivery=api("deliverStandingEffect",server,PLAYER,community,20,id("ultima_acceptance:deed"),"integration-deed",1L,"integration test");
        System.out.println("Integration reputation delivery: "+delivery);
        check(((Number)api("getScoreOrZero",server,PLAYER,community)).intValue()==20,"real reputation canonical local deed committed");
        schedule.accept(15L,()->{
            try{
                check(score(factions,A)==7&&acknowledged(server)==ackBefore,"SHADOW projects without canonical faction mutation or source acknowledgement");
                check(factions.diagnosticSummary().contains("shadow=1"),"SHADOW projection retained for diagnostics");
                kingdoms.setKingdom(settlement.id(),B);
                FactionConfig.SYNC_MODE.set(FactionConfig.SyncMode.MCA_TO_FACTION);
            }catch(Exception e){throw new RuntimeException(e);}
        });
        schedule.accept(35L,()->{
            try{
                check(score(factions,A)==17&&score(factions,B)==0,"event-time kingdom freezeHistory survives reassignment before delivery");
                var cursor=factions.durableSourceCursor(CONSUMER.toString()).orElseThrow();
                check(cursor.through()>ackBefore&&acknowledged(server)==cursor.through(),"source acknowledged only through durable target cursor");
                check(((Number)api("getScoreOrZero",server,PLAYER,community)).intValue()==20,"one-way faction projection leaves local canonical score unchanged");
                server.saveEverything(false,true,true);
                System.out.println("CRASH_READY integration durable source/target/ack; awaiting process kill");
            }catch(Exception e){throw new RuntimeException(e);}
        });
    }
}
