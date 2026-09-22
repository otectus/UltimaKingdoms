package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.compat.mca.McaFamilyEvidence;
import com.ultimakingdoms.evolution.*;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.FakePlayer;

import java.util.*;
import java.util.function.Consumer;

import static com.ultimakingdoms.acceptance.IntegrationScenario.call;
import static com.ultimakingdoms.acceptance.IntegrationScenario.check;

/** Packaged acceptance for read-only use of MCA's reciprocal family tree. */
final class R4FamilyScenario {
    private static final String A="ultima_kingdoms:serenum",B="ultima_kingdoms:lunari";

    @SuppressWarnings({"unchecked","rawtypes"})
    static void run(MinecraftServer server, Consumer<String> finish) throws Exception {
        var level=server.overworld();var kingdoms=UltimaKingdomsApi.get(server);var politics=UltimaPoliticsApi.get(server);
        var actor=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4FamilyActor")){@Override public boolean hasPermissions(int n){return n<=2;}};
        var spouse=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4FamilySpouse"));
        var outsider=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4FamilyOutsider"));
        actor.setPos(1,64,1);spouse.setPos(161,64,1);outsider.setPos(2,64,1);

        Map<UUID,ServerPlayer> online=null;
        for(var field:PlayerList.class.getDeclaredFields())if(field.getGenericType().getTypeName().contains("java.util.UUID")&&field.getGenericType().getTypeName().contains("ServerPlayer")){
            field.setAccessible(true);online=(Map<UUID,ServerPlayer>)field.get(server.getPlayerList());break;}
        check(online!=null,"R4 family fixture locates authenticated online registry");
        for(var player:List.of(actor,spouse,outsider)){online.put(player.getUUID(),player);server.getProfileCache().add(player.getGameProfile());}
        boolean actorLoaded=false,spouseLoaded=false;
        try{
            level.addNewPlayer(actor);actorLoaded=true;level.addNewPlayer(spouse);spouseLoaded=true;
            check(level.getEntity(actor.getUUID())==actor&&level.getEntity(spouse.getUUID())==spouse,"native family participants are loaded adult players");

            var seatA=MilitaryScenario.settlement(kingdoms,level,new BlockPos(0,64,0),new ResourceLocation(A),"R4 Family Home");
            var seatB=MilitaryScenario.settlement(kingdoms,level,new BlockPos(160,64,0),new ResourceLocation(B),"R4 Family Spouse Home");
            success(politics.execute(actor,request(politics,Action.BOOTSTRAP,A,A+"_charter",seatA.id().toString(),actor)));
            success(politics.execute(actor,request(politics,Action.BOOTSTRAP,B,B+"_charter",seatB.id().toString(),spouse)));
            kingdoms.setResidence(actor,seatA.id());kingdoms.setResidence(spouse,seatB.id());
            for(var viewer:List.of(actor,outsider))for(var settlement:List.of(seatA,seatB))SettlementKnowledge.get(server).discover(viewer.getUUID(),settlement.id());

            Class<?> treeType=Class.forName("forge.net.mca.server.world.data.FamilyTree");
            Class<?> genderType=Class.forName("forge.net.mca.entity.ai.relationship.Gender");
            Class<?> relationshipType=Class.forName("forge.net.mca.entity.ai.relationship.RelationshipState");
            Object tree=treeType.getMethod("get",net.minecraft.server.level.ServerLevel.class).invoke(null,level);
            Object male=Enum.valueOf((Class<? extends Enum>)genderType.asSubclass(Enum.class),"MALE");
            Object female=Enum.valueOf((Class<? extends Enum>)genderType.asSubclass(Enum.class),"FEMALE");
            var create=treeType.getMethod("getOrCreate",UUID.class,String.class,genderType,boolean.class);
            Object first=create.invoke(tree,actor.getUUID(),actor.getName().getString(),male,true);
            Object second=create.invoke(tree,spouse.getUUID(),spouse.getName().getString(),female,true);
            first.getClass().getMethod("updatePartner",first.getClass()).invoke(first,second);
            second.getClass().getMethod("updatePartner",second.getClass()).invoke(second,first);
            check((boolean)call(first,"isPlayer")&&(boolean)call(second,"isPlayer")&&!(boolean)call(first,"isDeceased")&&!(boolean)call(second,"isDeceased")
                    &&call(first,"partner").equals(spouse.getUUID())&&call(second,"partner").equals(actor.getUUID())
                    &&(boolean)call(call(first,"getRelationshipState"),"isMarried")&&(boolean)call(call(second,"getRelationshipState"),"isMarried"),
                    "MCA owner API stores a reciprocal living-player marriage");

            CompoundTag firstBefore=((CompoundTag)call(first,"save")).copy(),secondBefore=((CompoundTag)call(second,"save")).copy();
            var marriage=McaFamilyEvidence.marriage(server,actor.getUUID()).orElseThrow();
            check(marriage.second().equals(spouse.getUUID())&&firstBefore.equals(call(first,"save"))&&secondBefore.equals(call(second,"save")),
                    "R4 family evidence reads the reciprocal native tree without mutation");

            var evolution=EvolutionRuntime.get(server);evolution.configure(actor,true,false);evolution.region(actor,seatA.id(),true);
            String opened=evolution.familyIntroduction(actor);UUID scenario=UUID.fromString(opened.split(" ")[3].replace(".",""));
            check(evolution.page(actor,0).stream().anyMatch(line->line.contains(scenario.toString()))
                    &&evolution.page(outsider,0).stream().noneMatch(line->line.contains(scenario.toString())),
                    "family introduction uses separate civic residences and remains private to its audience");
            evolution.contribute(actor,scenario,1,EvolutionState.Outcome.INTRODUCE);
            check(firstBefore.equals(call(first,"save"))&&secondBefore.equals(call(second,"save")),
                    "family-backed introduction revalidation does not mutate MCA relationship state");

            int forcedBefore=level.getForcedChunks().size();
            level.removePlayerImmediately(spouse,Entity.RemovalReason.DISCARDED);spouseLoaded=false;
            boolean unloadedRefused=false;try{evolution.familyIntroduction(actor);}catch(IllegalArgumentException expected){unloadedRefused=expected.getMessage().contains("spouse is loaded");}
            check(unloadedRefused&&level.getForcedChunks().size()==forcedBefore&&McaFamilyEvidence.marriage(server,actor.getUUID()).isPresent()
                    &&!(boolean)call(second,"isDeceased"),"unloaded spouse pauses introduction without forced chunks or fabricated death");

            Object single=Enum.valueOf((Class<? extends Enum>)relationshipType.asSubclass(Enum.class),"SINGLE");
            first.getClass().getMethod("setRelationshipState",relationshipType).invoke(first,single);
            second.getClass().getMethod("setRelationshipState",relationshipType).invoke(second,single);
            check(McaFamilyEvidence.marriage(server,actor.getUUID()).isEmpty(),"native relationship invalidation is observed fail-closed");
            boolean resolutionRefused=false;try{evolution.resolve(actor,scenario,2,EvolutionState.Outcome.INTRODUCE,B);}catch(IllegalArgumentException expected){resolutionRefused=expected.getMessage().contains("relationship changed");}
            check(resolutionRefused&&politics.page(actor,UUID.randomUUID(),A,"petitions",0).rows().isEmpty(),
                    "invalidated marriage prevents unresolved family-backed political operation");
            finish.accept("PASS integration R4 native MCA family: reciprocal owner state, read-only evidence, private introduction, invalidation and unloaded-spouse refusal");
        }finally{
            if(spouseLoaded)level.removePlayerImmediately(spouse,Entity.RemovalReason.DISCARDED);
            if(actorLoaded)level.removePlayerImmediately(actor,Entity.RemovalReason.DISCARDED);
            for(var player:List.of(actor,spouse,outsider))online.remove(player.getUUID());
        }
    }

    private static Request request(PoliticalService service,Action action,String kingdom,String definition,String target,ServerPlayer person){
        return new Request(UUID.randomUUID(),service.revision(),action,kingdom,definition,target,new Person(person.getUUID(),Kind.PLAYER),"","","",0,0,0);
    }
    private static void success(Result result){check(result.success(),"political family fixture: "+result.message());}
    private R4FamilyScenario(){}
}
