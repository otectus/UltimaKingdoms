package com.ultimakingdoms.acceptance;

import com.google.gson.JsonParser;
import com.ultimakingdoms.api.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Exercises shipped native TopicGate, chat preview and DynamicHub against an MCA entity. */
final class NativeConversationScenario {
    static final String PREFIX="dev.otectus.mcaconversations.";
    static Object call(Object o,String method,Object...args)throws Exception{return IntegrationScenario.call(o,method,args);}
    @SuppressWarnings({"rawtypes","unchecked"})
    static void run(MinecraftServer server,ServerPlayer player,SettlementView settlement)throws Exception {
        var kingdoms=UltimaKingdomsApi.get(server);var pos=settlement.anchor();var level=server.overworld();level.getChunkAt(pos);
        Entity giver=Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getValue(IntegrationScenario.id("mca:male_villager"))).create(level);
        ((Mob)giver).setNoAi(true);giver.moveTo(pos.getX(),pos.getY(),pos.getZ());
        Class age=Class.forName("forge.net.mca.entity.ai.relationship.AgeState");giver.getClass().getMethod("setAgeState",age).invoke(giver,Enum.valueOf(age,"ADULT"));level.addFreshEntity(giver);kingdoms.setResidence(giver,settlement.id());
        Class<?> loader=Class.forName(PREFIX+"conversation.ConversationCatalogLoader"),catalogClass=Class.forName(PREFIX+"conversation.ConversationCatalog"),entryClass=Class.forName(PREFIX+"conversation.TopicEntry");
        Object original=loader.getMethod("active").invoke(null);Object selected=null;
        var constraints=Class.forName(PREFIX+"compat.McaCompat").getMethod("checkConstraints",Entity.class,ServerPlayer.class,String.class,String.class);
        var topicAllows=Class.forName(PREFIX+"conversation.TopicGate").getMethod("allows",entryClass,Entity.class,ServerPlayer.class);
        for(Object entry:(Collection<?>)call(original,"topics"))if((boolean)topicAllows.invoke(null,entry,giver,player)&&(boolean)constraints.invoke(null,giver,player,call(entry,"entryQuestion"),call(entry,"entryAnswer"))){selected=entry;break;}
        IntegrationScenario.check(selected!=null,"native Conversations has an executable adult catalog starter");
        Class<?> gateClass=Class.forName(PREFIX+"conversation.KingdomGateSpec");
        Object gate=gateClass.getMethod("fromJson",com.google.gson.JsonElement.class).invoke(null,JsonParser.parseString("{\"subject\":\"giver_residence\",\"include\":[\"ultima_kingdoms:serenum\"]}"));
        var components=entryClass.getRecordComponents();Object[] values=new Object[components.length];Class<?>[] types=new Class[components.length];
        for(int i=0;i<components.length;i++){types[i]=components[i].getType();values[i]=components[i].getAccessor().invoke(selected);if(components[i].getName().equals("kingdomGate"))values[i]=Optional.of(gate);}
        Object authored=entryClass.getConstructor(types).newInstance(values);Object catalog=catalogClass.getMethod("build",Collection.class).invoke(null,List.of(authored));loader.getMethod("setActiveForTesting",catalogClass).invoke(null,catalog);
        Class<?> kind=Class.forName(PREFIX+"hub.HubSlot$Kind"),domain=Class.forName(PREFIX+"hub.HubDomain"),slot=Class.forName(PREFIX+"hub.HubSlot"),planClass=Class.forName(PREFIX+"hub.HubPlan");
        Object hubSlot=slot.getConstructor(kind,domain,String.class).newInstance(kind.getEnumConstants()[0],domain.getEnumConstants()[0],call(authored,"id"));Object plan=planClass.getConstructor(List.class).newInstance(List.of(hubSlot));
        var filter=Class.forName(PREFIX+"hub.DynamicHub").getDeclaredMethod("withoutUnavailableTopics",planClass,Entity.class,ServerPlayer.class);filter.setAccessible(true);
        Class<?> scoredClass=Class.forName(PREFIX+"chat.IntentMatcher$Scored");Object scored=scoredClass.getConstructor(String.class,double.class,String.class,String.class,String.class,String.class,boolean.class).newInstance("integration",1.0,call(authored,"entryQuestion"),call(authored,"entryAnswer"),null,null,false);
        var preview=Class.forName(PREFIX+"chat.GatePreview").getMethod("eligible",Entity.class,ServerPlayer.class,scoredClass);
        IntegrationScenario.check((boolean)topicAllows.invoke(null,authored,giver,player)&&!((List<?>)call(filter.invoke(null,plan,giver,player),"slots")).isEmpty()&&(boolean)preview.invoke(null,giver,player,scored),"native Conversations allows eligible topic in catalog, dynamic hub and chat preview");
        kingdoms.setKingdom(settlement.id(),IntegrationScenario.B);
        IntegrationScenario.check(!(boolean)topicAllows.invoke(null,authored,giver,player)&&((List<?>)call(filter.invoke(null,plan,giver,player),"slots")).isEmpty()&&!(boolean)preview.invoke(null,giver,player,scored),"native Conversations removes restricted hub topic and rejects chat after reassignment");
        kingdoms.setKingdom(settlement.id(),IntegrationScenario.A);loader.getMethod("setActiveForTesting",catalogClass).invoke(null,original);giver.discard();
    }
}
