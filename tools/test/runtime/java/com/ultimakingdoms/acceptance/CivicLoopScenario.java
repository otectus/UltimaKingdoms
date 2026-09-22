package com.ultimakingdoms.acceptance;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.civic.*;
import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.api.townstead.*;
import com.ultimakingdoms.civic.*;
import com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import static com.ultimakingdoms.acceptance.IntegrationScenario.check;

/** Actual native quest completion + provider receipt, then isolated fixtures for regional service qualification. */
final class CivicLoopScenario {
    static final ResourceLocation ORG=new ResourceLocation("ultima_kingdoms:lamplighters");
    static final UUID PLAYER=UUID.fromString("71fe9da5-4874-4f46-81e6-a55c191fe912");
    record Place(SettlementView settlement,Entity npc,Object building) { }
    @SuppressWarnings({"unchecked","rawtypes"})
    static Place place(MinecraftServer server,int villageId,BlockPos pos,String name)throws Exception {
        var level=server.overworld();var kingdoms=UltimaKingdomsApi.get(server);level.getChunkAt(pos);level.setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);
        var settlement=kingdoms.registerCandidate(level,SettlementCandidate.external(level.dimension(),pos,32,SettlementBounds.around(pos,32),new ResourceLocation("ultima_acceptance:civic_loop"),name,new ResourceLocation("ultima_kingdoms:serenum"),Map.of("mca","minecraft:overworld#"+villageId),name));
        var factory=com.ultimakingdoms.test.McaGameTests.class.getDeclaredMethod("village",net.minecraft.server.level.ServerLevel.class,int.class,BlockPos.class);factory.setAccessible(true);
        var village=factory.invoke(null,level,villageId,pos);var buildings=(Map<Integer,Object>)IntegrationScenario.call(village,"getBuildings");
        Object workshop=buildings.get(0);IntegrationScenario.call(workshop,"setId",0);IntegrationScenario.call(workshop,"setType","workshop");
        for(String axis:new String[]{"X","Y","Z"})for(int side=0;side<=1;side++){
            var field=workshop.getClass().getDeclaredField("pos"+side+axis);field.setAccessible(true);
            int center=axis.equals("X")?pos.getX():axis.equals("Y")?pos.getY():pos.getZ();field.setInt(workshop,center+(side==0?-2:2));
        }
        Entity npc=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca:male_villager")).create(level);
        net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn((Mob)npc,level,level.getCurrentDifficultyAt(pos),MobSpawnType.COMMAND,null,null);
        ((AgeableMob)npc).setAge(0);Class age=Class.forName("forge.net.mca.entity.ai.relationship.AgeState");npc.getClass().getMethod("setAgeState",age).invoke(npc,Enum.valueOf(age,"ADULT"));
        ((VillagerDataHolder)npc).setVillagerData(((VillagerDataHolder)npc).getVillagerData().setProfession(VillagerProfession.CLERIC));
        ((Mob)npc).setNoAi(true);npc.moveTo(pos.getX(),pos.getY(),pos.getZ());level.addFreshEntity(npc);
        var home=com.ultimakingdoms.test.McaGameTests.class.getDeclaredMethod("home",Entity.class,int.class);home.setAccessible(true);home.invoke(null,npc,villageId);
        kingdoms.setOrigin(npc,settlement.id());kingdoms.setResidence(npc,settlement.id());return new Place(settlement,npc,workshop);
    }
    static void run(MinecraftServer server,String phase,BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        var player=new FakePlayer(server.overworld(),new GameProfile(PLAYER,"CivicLoop"));
        var org=OrganizationApi.get(server);var civic=CivicRuntime.get(server);var knowledge=SettlementKnowledge.get(server);
        if(phase.equals("civic-loop-restart")) {
            var member=org.ownSnapshot(player).memberships().get(0);
            check(member.standing()==100&&member.deedCount()==7&&member.status()==OrganizationMembershipSnapshot.Status.UNAFFILIATED,"neutral civic service persists after process restart");
            var destination=UltimaKingdomsApi.get(server).findSettlement("Civic Neighbor").orElseThrow();
            check(knowledge.visible(player,destination.id()),"introduction discovery survives restart");
            check(!org.ownHistory(player,32).isEmpty(),"private committed history survives restart");
            // Let the restored world's asynchronous entity reads finish before requesting shutdown.
            schedule.accept(80L,()->finish.accept("PASS integration civic loop restart: native receipt, neutral standing, history and introduction"));return;
        }
        server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL,true);
        var source=place(server,98101,new BlockPos(4000,-60,4000),"Civic Workshop");
        var target=place(server,98102,new BlockPos(4200,-60,4000),"Civic Neighbor");
        player.moveTo(4001,-60,4000);var stranger=new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"CivicStranger"));
        schedule.accept(40L,()->{try {
            check(UltimaTownsteadApi.get(server).buildingAt(server.overworld(),source.settlement.anchor()).orElseThrow().family().equals("workshop"),"actual Townstead workshop recognized");
            source.npc.getClass().getMethod("setProfession",VillagerProfession.class).invoke(source.npc,VillagerProfession.CLERIC);
            Object data=((Optional<?>)NativeQuestScenario.invoke("state.QuestCapabilities","get",player)).orElseThrow();
            NativeQuestScenario.invoke("api.McaQuestsApi","readCompletionReceipts",player,new ResourceLocation("ultima_kingdoms:regional_civic_network"),Integer.valueOf(8));
            String json=Files.readString(Path.of("fire_in_poor_hands.json"));
            Codec<?> codec=(Codec<?>)Class.forName(NativeQuestScenario.PREFIX+"quest.QuestDefinition").getField("CODEC").get(null);
            Object definition=codec.parse(JsonOps.INSTANCE,JsonParser.parseString(json)).getOrThrow(false,message->{throw new AssertionError(message);});
            NativeQuestScenario.register(definition);
            var pass=NativeQuestScenario.invoke("quest.OfferFilters$Pass","of",player,source.npc,data);
            System.out.println("CIVIC_OFFER "+NativeQuestScenario.invoke("quest.OfferFilters","explain",pass,definition));
            var offers=(List<?>)NativeQuestScenario.invoke("quest.OfferSessionService","currentOffers",player,source.npc,data,Set.of(NativeQuestScenario.call(definition,"id")));
            check(offers.size()==1,"actual authored quest remains eligible in a scoped offer session");
            check((boolean)NativeQuestScenario.invoke("quest.QuestManager","accept",player,source.npc,NativeQuestScenario.call(definition,"id")),"native authored quest accepted");
            var active=((List<?>)NativeQuestScenario.call(data,"active")).get(0);
            var firestick=ForgeRegistries.ITEMS.getValue(new ResourceLocation("survival_firesticks:firestick"));check(firestick!=null,"actual authored firestick item exists");
            ItemStack stack=new ItemStack(firestick,2);player.getInventory().add(stack.copy());
            var crafted=new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(player,stack,new net.minecraft.world.SimpleContainer(9));
            NativeQuestScenario.invoke("event.QuestProgressEvents","onItemCrafted",crafted);
            NativeQuestScenario.complete(player,source.npc,definition,active,data);
            CivicReceiptFaultFixture.deliverAcrossPolicyRemoval(player,source.settlement.id());
            QuestCompletionBridge.consume(player);
            var member=org.ownSnapshot(player).memberships().stream().filter(m->m.organizationId().equals(ORG)).findFirst().orElseThrow();
            check(member.standing()==10&&member.deedCount()==1&&!member.status().equals(OrganizationMembershipSnapshot.Status.ACTIVE),"actual crafted/delivered pack quest credits neutral guild service once");
            QuestCompletionBridge.consume(player);check(org.ownSnapshot(player).memberships().get(0).standing()==10,"provider replay does not duplicate native quest service");
            check(player.getInventory().countItem(firestick)==0,"native delivery consumed exactly two crafted firesticks");
            // Subsequent qualification fixtures isolate the service logic; only the first deed above claims native completion.
            var definitionView=org.definition(ORG).orElseThrow();
            for(var deed:definitionView.deeds()) {
                if(deed.questId().getPath().equals("civic/lamplighters/fire_in_poor_hands"))continue;
                org.recordDeed(player.getUUID(),ORG,UUID.randomUUID(),deed.questId().toString(),deed.credit(),Optional.of(source.settlement.id()));
                if(org.ownSnapshot(player).memberships().get(0).standing()>=100)break;
            }
            var sponsor=new ResourceLocation("ultima:civic/lamplighters/fire_in_poor_hands");
            check(civic.recordAuthoredContact(target.npc.getUUID(),ORG,target.settlement.id(),UUID.randomUUID(),sponsor),"neighbor authored contact fixture committed");
            player.moveTo(4201,-60,4000);check(civic.speakerContext(player,target.npc).orElseThrow().servicesAvailable(),"neighbor chapter activates from actual workshop");
            player.moveTo(4001,-60,4000);var context=civic.speakerContext(player,source.npc).orElseThrow();
            check(context.servicesAvailable()&&context.introductionQualified()&&context.commissionQualified(),"peaceful unaffiliated contributor earns real chapter services");
            check(!knowledge.visible(player,target.settlement.id()),"neighbor initially unknown");
            if(net.minecraftforge.fml.ModList.get().isLoaded("mcaconversations"))nativeConversation(player,stranger,source.npc,target.settlement.id());
            var result=CivicNetworkApi.requestIntroduction(player,source.npc);check(result.success()&&result.settlement().orElseThrow().equals(target.settlement.id()),"introduction reveals one actual neighbor");
            check(knowledge.visible(player,target.settlement.id())&&!knowledge.visible(stranger,target.settlement.id()),"introduction knowledge remains recipient-private");
            check(CivicNetworkApi.requestIntroduction(player,source.npc).settlement().equals(result.settlement()),"cooldown replay returns same destination");
            var before=source.npc.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            check(CivicNetworkApi.requestCommissions(player,source.npc).success(),"native curated commission menu opens for qualified neutral player");
            check(before.equals(source.npc.saveWithoutId(new net.minecraft.nbt.CompoundTag())),"guild service does not rewrite native NPC relationship data");
            IntegrationScenario.call(source.building,"setType","library");
            check(!CivicNetworkApi.requestIntroduction(player,source.npc).success(),"building change refuses stale service");
            IntegrationScenario.call(source.building,"setType","workshop");
            check(CivicNetworkApi.requestIntroduction(player,source.npc).success(),"restored provider building recovers service without deleting history");
            player.moveTo(4500,-60,4000);check(!CivicNetworkApi.requestCommissions(player,source.npc).success(),"out-of-range stale action denied");
            check(org.ownSnapshot(stranger).memberships().isEmpty()&&org.ownHistory(stranger,32).isEmpty(),"second player sees no private civic history");
            server.saveEverything(false,true,true);
            finish.accept("PASS integration civic loop: native pack craft/delivery receipt, neutral services, chapter recovery, private introduction and commissions");
        }catch(Exception failure){throw new RuntimeException(failure);}});
    }
    private static void nativeConversation(ServerPlayer player,ServerPlayer stranger,Entity npc,UUID destination)throws Exception {
        String prefix="dev.otectus.mcaconversations.";
        Object catalog=Class.forName(prefix+"conversation.ConversationCatalogLoader").getMethod("active").invoke(null);
        Object entry=((Optional<?>)NativeConversationScenario.call(catalog,"byStarter","conversations.cat.village","guild_contact")).orElseThrow();
        var gate=Class.forName(prefix+"conversation.TopicGate").getMethod("allows",entry.getClass(),Entity.class,ServerPlayer.class);
        check((boolean)gate.invoke(null,entry,npc,player),"native civic topic accepts actual nearby guild contact");
        check(!(boolean)gate.invoke(null,entry,npc,stranger),"native civic topic rejects remote speaker submission");
        var actions=Class.forName("forge.net.mca.resources.data.dialogue.Actions");
        check(((Map<?,?>)actions.getField("TYPES").get(null)).containsKey("conversations_civic"),"native MCA civic action is registered");
        Object action=actions.getMethod("fromJson",com.google.gson.JsonObject.class).invoke(null,JsonParser.parseString("{\"conversations_civic\":\"introduction\"}").getAsJsonObject());
        var trigger=actions.getMethod("trigger",Class.forName("forge.net.mca.entity.VillagerEntityMCA"),ServerPlayer.class);
        var before=npc.saveWithoutId(new net.minecraft.nbt.CompoundTag());trigger.invoke(action,npc,player);
        check(SettlementKnowledge.get(player.getServer()).visible(player,destination),"native conversation action grants actual private introduction");
        stranger.moveTo(player.getX(),player.getY(),player.getZ());trigger.invoke(action,npc,stranger);
        check(!SettlementKnowledge.get(player.getServer()).visible(stranger,destination),"unqualified second actor cannot reuse rendered native service action");
        check(before.equals(npc.saveWithoutId(new net.minecraft.nbt.CompoundTag())),"native civic action leaves MCA personality and relationship state unchanged");
    }

}
