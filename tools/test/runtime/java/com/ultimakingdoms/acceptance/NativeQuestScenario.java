package com.ultimakingdoms.acceptance;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.factions.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.registries.ForgeRegistries;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.*;

/** Uses the shipped QuestManager/OfferSessionService and real attached player capability. */
final class NativeQuestScenario {
    static final String PREFIX="dev.otectus.mcaquests.";
    static Object invoke(String type,String method,Object...args)throws Exception {
        for(Method m:Class.forName(PREFIX+type).getDeclaredMethods()){
            if(!m.getName().equals(method)||m.getParameterCount()!=args.length)continue;
            boolean match=true;for(int i=0;i<args.length;i++)if(args[i]!=null&&!(m.getParameterTypes()[i].isInstance(args[i]) || m.getParameterTypes()[i]==int.class && args[i] instanceof Integer))match=false;
            if(match){m.setAccessible(true);return m.invoke(null,args);}
        }
        throw new NoSuchMethodException(type+"."+method);
    }
    static Object call(Object o,String method,Object...args)throws Exception{return IntegrationScenario.call(o,method,args);}
    static void check(boolean value,String message){IntegrationScenario.check(value,"native quests: "+message);}
    static Object definition(String name,String mode,boolean reward)throws Exception {
        String json="{\"id\":\"ultima_acceptance:"+name+"\",\"giver\":{},\"dialogue\":{},\"objectives\":[],\"rewards\":"+(reward?"[{\"type\":\"ultima_kingdoms:faction_standing\",\"amount\":3},{\"type\":\"ultima_kingdoms:faction_standing\",\"amount\":4}]":"[]")+",\"kingdom_lifecycle\":{\"mode\":\""+mode+"\",\"failure_reason\":\"test.kingdom_changed\",\"gate\":{\"subject\":\"giver_residence\",\"include\":[\"ultima_kingdoms:serenum\"]}}}";
        Codec<?> codec=(Codec<?>)Class.forName(PREFIX+"quest.QuestDefinition").getField("CODEC").get(null);
        return codec.parse(JsonOps.INSTANCE,JsonParser.parseString(json)).getOrThrow(false,message->{throw new AssertionError(message);});
    }
    static void register(Object def)throws Exception {
        var method=Class.forName(PREFIX+"data.QuestRegistry").getDeclaredMethod("replaceAll",Map.class,List.class,List.class);method.setAccessible(true);
        method.invoke(null,Map.of((ResourceLocation)call(def,"id"),def),List.of(),List.of());
    }
    static Object accept(ServerPlayer player,Entity giver,Object data,Object def)throws Exception {
        register(def);
        List<?> offers=(List<?>)invoke("quest.OfferSessionService","currentOffers",player,giver,data);
        check(offers.size()==1,"actual offer draw includes eligible authored definition");
        check((boolean)invoke("quest.QuestManager","accept",player,giver,call(def,"id")),"QuestManager accepts current server-owned offer");
        return ((List<?>)call(data,"active")).get(0);
    }
    static void complete(ServerPlayer player,Entity giver,Object def,Object active,Object data)throws Exception {
        check((boolean)invoke("quest.QuestManager","isComplete",player,def,active),"actual completion predicate accepts earned quest");
        check((boolean)invoke("quest.QuestManager","completeQuest",player,giver,def,active,data),"actual reward completion removes active quest");
        check(((List<?>)call(data,"active")).isEmpty(),"completed quest removed from player capability");
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    static void run(MinecraftServer server,ServerPlayer player,SettlementView settlement,BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        var kingdoms=UltimaKingdomsApi.get(server);var factions=UltimaFactionsApi.get(server);
        var level=server.overworld();var pos=settlement.anchor();level.getChunkAt(pos);
        level.setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);
        Entity giver=Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca:male_villager"))).create(level);
        ((Mob)giver).setNoAi(true);giver.moveTo(pos.getX(),pos.getY(),pos.getZ());
        Class age=Class.forName("forge.net.mca.entity.ai.relationship.AgeState");giver.getClass().getMethod("setAgeState",age).invoke(giver,Enum.valueOf(age,"ADULT"));
        level.addFreshEntity(giver);kingdoms.setOrigin(giver,settlement.id());kingdoms.setResidence(giver,settlement.id());
        Object data=((Optional<?>)invoke("state.QuestCapabilities","get",player)).orElseThrow();
        Object offer=definition("offer_only","offer_only",false);Object active=accept(player,giver,data,offer);
        kingdoms.setKingdom(settlement.id(),IntegrationScenario.B);complete(player,giver,offer,active,data);
        check(true,"OFFER_ONLY completes after political transfer");
        kingdoms.setKingdom(settlement.id(),IntegrationScenario.A);
        Object bound=definition("bound_reward","bound_at_accept",true);Object boundActive=accept(player,giver,data,bound);
        int before=factions.getStanding(player.getUUID(),IntegrationScenario.A).map(FactionStandingSnapshot::score).orElse(0);
        kingdoms.setKingdom(settlement.id(),IntegrationScenario.B);complete(player,giver,bound,boundActive,data);
        check(factions.getStanding(player.getUUID(),IntegrationScenario.A).orElseThrow().score()==before+7,"two distinct faction rewards commit to frozen acceptance kingdom");
        check(!(boolean)invoke("quest.QuestManager","completeQuest",player,giver,bound,boundActive,data),"repeated completion request cannot pay again");
        check(factions.getStanding(player.getUUID(),IntegrationScenario.A).orElseThrow().score()==before+7,"replayed completion leaves canonical standing unchanged");
        kingdoms.setKingdom(settlement.id(),IntegrationScenario.A);
        Object failure=definition("failure_on_change","fail_on_change",false);Object failureActive=accept(player,giver,data,failure);
        giver.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        schedule.accept(5L,()->{try{
            check(level.getEntity(giver.getUUID())==null,"giver is absent from loaded entity lookup");
            invoke("event.QuestProgressEvents","checkFailureTriggers",player);
            check(((List<?>)call(data,"active")).contains(failureActive),"unloaded giver does not cancel FAIL_ON_CHANGE quest");
            Entity returned=Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca:male_villager"))).create(level);
            ((Mob)returned).setNoAi(true);returned.setUUID(giver.getUUID());returned.moveTo(pos.getX(),pos.getY(),pos.getZ());check(level.addFreshEntity(returned),"unloaded giver re-added successfully");kingdoms.setResidence(returned,settlement.id());
            kingdoms.setKingdom(settlement.id(),IntegrationScenario.B);
            schedule.accept(5L,()->{try{
                check(level.getEntity(giver.getUUID())==returned,"returned giver is visible to native lifecycle lookup");
                invoke("event.QuestProgressEvents","checkFailureTriggers",player);
                check(((List<?>)call(data,"active")).isEmpty(),"confirmed political transfer fails FAIL_ON_CHANGE through actual polling path");
                finish.accept("PASS integration native QuestManager offer/accept/lifecycle/rewards/replay/unloaded giver");
            }catch(Exception e){throw new RuntimeException(e);}});
        }catch(Exception e){throw new RuntimeException(e);}});
    }
}
