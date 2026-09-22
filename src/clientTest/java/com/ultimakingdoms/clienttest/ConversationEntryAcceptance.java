package com.ultimakingdoms.clienttest;

import com.google.gson.JsonParser;
import com.ultimakingdoms.api.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;
import java.lang.reflect.*;
import java.util.*;

/** Real C2S MCA and numbered selection packets, plus final chat execution gate. */
final class ConversationEntryAcceptance {
    static final String P="dev.otectus.mcaconversations.";
    volatile boolean ready, finished;
    volatile long revision;
    Entity giver; String question,answer; Object original,offer; Class<?> loader,catalog;
    MinecraftServer server; UUID settlement;
    static Object call(Object target,String method,Object...args)throws Exception {
        for(Method m:target.getClass().getMethods()) if(m.getName().equals(method)&&m.getParameterCount()==args.length)return m.invoke(target,args);
        throw new NoSuchMethodException(method);
    }
    static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);System.out.println("[ConversationEntryAcceptance] PASS "+text);}
    @SuppressWarnings({"unchecked","rawtypes"}) void prepare(MinecraftServer server,UUID settlement)throws Exception {
        this.server=server;this.settlement=settlement;var player=player();var level=server.overworld();var kingdoms=UltimaKingdomsApi.get(server);
        kingdoms.setKingdom(settlement,new ResourceLocation("ultima_kingdoms:serenum"));
        giver=Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca:male_villager"))).create(level);
        ((Mob)giver).setNoAi(true);giver.moveTo(player.getX()+1,player.getY(),player.getZ());
        Class age=Class.forName("forge.net.mca.entity.ai.relationship.AgeState");giver.getClass().getMethod("setAgeState",age).invoke(giver,Enum.valueOf(age,"ADULT"));
        level.addFreshEntity(giver);kingdoms.setResidence(giver,settlement);
        Object interactions=giver.getClass().getMethod("getInteractions").invoke(giver);Field interacting=Class.forName("forge.net.mca.entity.interaction.EntityCommandHandler").getDeclaredField("interactingPlayer");interacting.setAccessible(true);interacting.set(interactions,player);
        loader=Class.forName(P+"conversation.ConversationCatalogLoader");catalog=Class.forName(P+"conversation.ConversationCatalog");Class<?> entryType=Class.forName(P+"conversation.TopicEntry");original=loader.getMethod("active").invoke(null);
        Object selected=null;
        Method constraints=Class.forName(P+"compat.McaCompat").getMethod("checkConstraints",Entity.class,ServerPlayer.class,String.class,String.class);
        for(Object entry:(Collection<?>)call(original,"topics")) if((boolean)Class.forName(P+"conversation.TopicGate").getMethod("allows",entryType,Entity.class,ServerPlayer.class).invoke(null,entry,giver,player)&&(boolean)constraints.invoke(null,giver,player,call(entry,"entryQuestion"),call(entry,"entryAnswer"))){selected=entry;break;}
        check(selected!=null,"adult live villager has executable catalog starter");
        Class<?> gateType=Class.forName(P+"conversation.KingdomGateSpec");Object gate=gateType.getMethod("fromJson",com.google.gson.JsonElement.class).invoke(null,JsonParser.parseString("{\"subject\":\"giver_residence\",\"include\":[\"ultima_kingdoms:serenum\"]}"));
        var components=entryType.getRecordComponents();Object[] values=new Object[components.length];Class<?>[] types=new Class[components.length];
        for(int i=0;i<components.length;i++){types[i]=components[i].getType();values[i]=components[i].getAccessor().invoke(selected);if(components[i].getName().equals("kingdomGate"))values[i]=Optional.of(gate);}
        Object authored=entryType.getConstructor(types).newInstance(values);Object testCatalog=catalog.getMethod("build",Collection.class).invoke(null,List.of(authored));loader.getMethod("setActiveForTesting",catalog).invoke(null,testCatalog);
        question=(String)call(authored,"entryQuestion");answer=(String)call(authored,"entryAnswer");
        check((boolean)Class.forName(P+"conversation.TopicGate").getMethod("allows",String.class,String.class,Entity.class,ServerPlayer.class).invoke(null,question,answer,giver,player),"authored topic initially eligible");
        newOffer();kingdoms.setKingdom(settlement,new ResourceLocation("ultima_kingdoms:lunari"));
        check((boolean)constraints.invoke(null,giver,player,question,answer),"native MCA constraints still allow after political reassignment");
        ready=true;
    }
    void newOffer()throws Exception {
        Class<?> frontend=Class.forName(P+"conversation.ConversationSession$Frontend");
        offer=Class.forName(P+"conversation.ConversationSessions").getMethod("recordOffer",UUID.class,UUID.class,String.class,List.class,frontend,long.class).invoke(null,player().getUUID(),giver.getUUID(),question,List.of(answer),frontend.getEnumConstants()[0],server.overworld().getGameTime());
        revision=((Number)call(offer,"revision")).longValue();
    }
    void sendMcaPacket()throws Exception {
        Object packet=Class.forName("forge.net.mca.network.c2s.InteractionDialogueMessage").getConstructor(UUID.class,String.class,String.class).newInstance(giver.getUUID(),question,answer);
        Class.forName("forge.net.mca.cobalt.network.NetworkHandler").getMethod("sendToServer",Class.forName("forge.net.mca.cobalt.network.Message")).invoke(null,packet);
    }
    void sendNumberedPacket()throws Exception {
        Class<?> ref=Class.forName(P+"network.ConversationRef");Object handle=ref.getConstructor(UUID.class,UUID.class).newInstance(null,giver.getUUID());
        Object packet=Class.forName(P+"network.ChoiceSelectC2S").getConstructor(ref,long.class,int.class).newInstance(handle,revision,0);
        ((SimpleChannel)Class.forName(P+"network.ConversationsNetwork").getField("CHANNEL").get(null)).sendToServer(packet);
    }
    boolean receivedRefusal(String route)throws Exception {
        Object state=Class.forName(P+"client.dialogue.ClientChoiceMessages").getMethod("state").invoke(null);
        Optional<?> lapse=(Optional<?>)call(state,"lapse");if(lapse.isEmpty() || ((Number)call(lapse.get(),"revision")).longValue()!=revision)return false;
        check(call(lapse.get(),"reason").toString().equals("REQUIREMENTS_CHANGED"),route+" returned real server refusal REQUIREMENTS_CHANGED");return true;
    }
    void verifyUnconsumed()throws Exception {
        Object session=((Optional<?>)Class.forName(P+"conversation.ConversationSessions").getMethod("raw",UUID.class).invoke(null,player().getUUID())).orElseThrow();
        Object live=((Optional<?>)call(session,"currentOffer")).orElseThrow();
        check(((Number)call(live,"revision")).longValue()==revision&&!(boolean)call(live,"consumed"),"restricted entry executed no answer and left offer unconsumed");
    }
    void finishChat()throws Exception {
        verifyUnconsumed();
        Object result=Class.forName(P+"chat.ChatModeDispatcher").getMethod("selectOfferedChoice",Entity.class,ServerPlayer.class,String.class,String.class,long.class).invoke(null,giver,player(),question,answer,server.overworld().getGameTime());
        check(result.toString().equals("REQUIREMENTS_CHANGED"),"direct chat final dispatch rejected politically hidden topic");
        loader.getMethod("setActiveForTesting",catalog).invoke(null,original);giver.discard();finished=true;
    }
    ServerPlayer player(){return server.getPlayerList().getPlayers().get(0);}
}
