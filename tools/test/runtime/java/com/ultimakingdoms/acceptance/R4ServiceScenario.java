package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.*;
import com.google.gson.JsonParser;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.civic.CivicRuntime;
import com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge;
import com.ultimakingdoms.evolution.*;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.contracts.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.*;
import net.minecraftforge.common.util.FakePlayer;
import java.util.*;
import java.util.function.*;
import static com.ultimakingdoms.acceptance.IntegrationScenario.*;

/** A real repeatable native commission must discharge only its recorded voluntary obligation. */
final class R4ServiceScenario {
    @SuppressWarnings("unchecked")
    static void run(MinecraftServer server, BiConsumer<Long,Runnable> schedule, Consumer<String> finish) throws Exception {
        var level = server.overworld();
        var steward = new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4Steward")) { @Override public boolean hasPermissions(int n) { return n<=2; } };
        var neighbor = new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4Neighbor"));
        var worker = new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4Volunteer"));
        Map<UUID,ServerPlayer> players = null;
        for (var field:PlayerList.class.getDeclaredFields()) if(field.getGenericType().getTypeName().contains("java.util.UUID") && field.getGenericType().getTypeName().contains("ServerPlayer")) {
            field.setAccessible(true); players=(Map<UUID,ServerPlayer>)field.get(server.getPlayerList()); break;
        }
        check(players!=null,"online registry available");
        var registry=players;
        for(var p:List.of(steward,neighbor)) { registry.put(p.getUUID(),p);server.getProfileCache().add(p.getGameProfile()); }
        var pos=new BlockPos(7800,-60,7800);
        var place=CivicLoopScenario.place(server,98702,pos,"R4 Voluntary Workshop");
        for(var p:List.of(steward,neighbor,worker)) { p.moveTo(pos.getX()+1,pos.getY(),pos.getZ());SettlementKnowledge.get(server).discover(p.getUUID(),place.settlement().id()); }
        String a="ultima_kingdoms:serenum",b="ultima_kingdoms:lunari";
        var second=MilitaryScenario.settlement(UltimaKingdomsApi.get(server),level,pos.offset(160,0,0),new ResourceLocation(b),"R4 Neighbor");
        var politics=UltimaPoliticsApi.get(server);
        for(var seat:List.of(place.settlement(),second)) {
            var leader=seat==second?neighbor:steward;String kingdom=seat.kingdomId().toString();
            R2InstitutionScenario.success(politics.execute(steward,R2InstitutionScenario.request(politics,Politics.Action.BOOTSTRAP,kingdom,kingdom+"_charter",seat.id().toString(),new Politics.Person(leader.getUUID(),Politics.Kind.PLAYER),"","",BlockPos.ZERO)));
        }
        schedule.accept(40L,()->{try {
            var recognized=R2InstitutionScenario.success(politics.execute(steward,R2InstitutionScenario.request(politics,Politics.Action.RECOGNIZE,a,"ultima_kingdoms:guild","",null,"","",pos)));
            var civic=CivicRuntime.get(server);UUID institution=UUID.fromString(recognized.recordId());
            check(civic.charter(steward,new ResourceLocation("ultima_kingdoms:lamplighters"),institution).success(),"R4 native workshop charter");
            var chapter=civic.knownChapters(steward,0).stream().filter(c->c.institution().equals(institution)).findFirst().orElseThrow();
            check(civic.appoint(steward,chapter.id(),place.npc()).success(),"R4 native contact");
            var protection=new ProtectionService(server);
            UUID pact=id(protection.propose(steward,a,b,place.settlement().id(),Set.of(ProtectionState.Duty.CIVIC_AID),168000,1200,"Workshop assistance"),"proposal ");
            protection.sign(steward,pact,a,1);protection.sign(neighbor,pact,b,2);
            UUID obligation=id(protection.request(steward,pact,ProtectionState.Duty.CIVIC_AID,3),"Obligation ");
            ResourceLocation quest=new ResourceLocation("ultima:evolution/aid");
            String json;
            try(var reader=server.getResourceManager().openAsReader(new ResourceLocation("ultima:mcaquests/quests/evolution/aid.json"))) { json=new String(reader.lines().reduce("",(x,y)->x+y)); }
            Codec<?> codec=(Codec<?>)Class.forName(NativeQuestScenario.PREFIX+"quest.QuestDefinition").getField("CODEC").get(null);
            Object definition=codec.parse(JsonOps.INSTANCE,JsonParser.parseString(json)).getOrThrow(false,m->{throw new AssertionError(m);});
            NativeQuestScenario.register(definition);
            check(!CivilianContractService.offerScoped(worker,place.npc(),CivilianContractKind.EVOLVING_AID,UUID.randomUUID()).success(),"arbitrary reward scope denied");
            var offer=CivilianContractService.offerScoped(worker,place.npc(),CivilianContractKind.EVOLVING_AID,obligation);
            check(offer.success(),"obligation opens native aid: "+offer.reason());
            check((boolean)NativeQuestScenario.invoke("quest.QuestManager","accept",worker,place.npc(),quest),"native scoped acceptance");
            Object data=((Optional<?>)NativeQuestScenario.invoke("state.QuestCapabilities","get",worker)).orElseThrow();
            Object active=((List<?>)NativeQuestScenario.call(data,"active")).get(0);
            worker.getInventory().add(new ItemStack(Items.BREAD,16));
            NativeQuestScenario.complete(worker,place.npc(),definition,active,data);
            check(worker.getInventory().countItem(Items.BREAD)==0 && worker.getInventory().countItem(Items.EMERALD)==6,"native items consumed and payment exactly once");
            QuestCompletionBridge.consume(worker);QuestCompletionBridge.consume(worker);
            check(CivilianContractService.scopedProof(server,worker.getUUID(),place.settlement().id(),CivilianContractKind.EVOLVING_AID,obligation).isPresent(),"durable scoped proof");
            protection.fulfill(worker,obligation,1);
            check(protection.inspect(steward,pact).stream().anyMatch(s->s.contains("SATISFIED")),"operational vassal duty fulfilled by neutral native service");
            boolean denied=false;try{protection.fulfill(worker,obligation,1);}catch(IllegalArgumentException expected){denied=true;}
            check(denied && worker.getInventory().countItem(Items.EMERALD)==6,"replay cannot duplicate duty or payment");
            check(!CivilianContractService.offerScoped(worker,place.npc(),CivilianContractKind.EVOLVING_AID,obligation).success(),"closed obligation cannot farm repeatable rewards");
            server.saveEverything(false,true,true);
            finish.accept("PASS integration R4 native scoped service: real delivery/reward, voluntary obligation fulfillment, invalid scope and duplicate denial");
        }catch(Throwable failure){failure.printStackTrace();finish.accept("FAIL "+failure);}finally{registry.remove(steward.getUUID());registry.remove(neighbor.getUUID());}});
    }
    private static UUID id(String line,String prefix){int start=line.indexOf(prefix)+prefix.length();return UUID.fromString(line.substring(start,start+36));}
}
