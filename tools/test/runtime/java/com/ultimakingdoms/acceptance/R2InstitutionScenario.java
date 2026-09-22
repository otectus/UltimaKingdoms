package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.civic.InstitutionalCommissionApi;
import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.civic.*;
import com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraftforge.common.util.FakePlayer;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import static com.ultimakingdoms.acceptance.IntegrationScenario.check;

/** Packaged native quest/payment/recognition loop. Only fixture construction uses reflection. */
final class R2InstitutionScenario {
    static final String A="ultima_kingdoms:serenum", B="ultima_kingdoms:lunari";
    static final UUID PLAYER=UUID.fromString("79000000-0000-0000-0000-000000000001");
    static final ResourceLocation ORG=new ResourceLocation("ultima_kingdoms:lamplighters");
    static Request request(PoliticalService service,Action action,String kingdom,String definition,String target,
                           Person person,String counterpart,String hash,BlockPos pos) {
        return new Request(UUID.randomUUID(),service.revision(),action,kingdom,definition,target,person,counterpart,"R2 civic acceptance",hash,pos.getX(),pos.getY(),pos.getZ());
    }
    static Result success(Result result) { check(result.success(),result.message());return result; }
    static UUID treaty(PoliticalService politics,ServerPlayer one,ServerPlayer two,String definition) {
        var proposal=success(politics.execute(one,request(politics,Action.PROPOSE,A,definition,"",null,B,"",BlockPos.ZERO)));
        String hash=politics.page(one,UUID.randomUUID(),A,"agreements",0).rows().stream().filter(r->r.id().equals(proposal.recordId())).findFirst().orElseThrow().termsHash();
        success(politics.execute(one,request(politics,Action.SIGN,A,"",proposal.recordId(),null,"",hash,BlockPos.ZERO)));
        success(politics.execute(two,request(politics,Action.SIGN,B,"",proposal.recordId(),null,"",hash,BlockPos.ZERO)));
        return UUID.fromString(proposal.recordId());
    }
    static void run(MinecraftServer server,String phase,BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        var player=new FakePlayer(server.overworld(),new GameProfile(PLAYER,"R2Artisan"));
        var operator=new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"R2Steward")) {
            @Override public boolean hasPermissions(int level) { return level<=2; }
        };
        var other=new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"R2Neighbor"));
        server.getProfileCache().add(operator.getGameProfile());server.getProfileCache().add(other.getGameProfile());
        var politics=UltimaPoliticsApi.get(server);var kingdoms=UltimaKingdomsApi.get(server);var civic=CivicRuntime.get(server);
        if(phase.equals("r2-legacy")) {
            Codec<?> codec=(Codec<?>)Class.forName(NativeQuestScenario.PREFIX+"quest.QuestDefinition").getField("CODEC").get(null);
            var parsed=codec.parse(JsonOps.INSTANCE,JsonParser.parseString(Files.readString(Path.of("workshop_lanterns.json"))));
            check(parsed.error().isPresent(),"old Quests rejects institutional template instead of exposing ordinary ungated work");
            check(!kingdoms.getKingdoms().isEmpty(),"base kingdom gameplay remains available with old optional provider");
            schedule.accept(80L,()->finish.accept("PASS integration R2 legacy provider: new template fails closed; base gameplay available"));return;
        }
        if(phase.equals("r2-restart")) {
            var savedPlayer=net.minecraft.nbt.NbtIo.readCompressed(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(PLAYER+".dat").toFile());
            int emeralds=0,lanterns=0;
            for(var tag:savedPlayer.getList("Inventory",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                var stack=ItemStack.of((net.minecraft.nbt.CompoundTag)tag);
                if(stack.is(Items.EMERALD))emeralds+=stack.getCount();
                if(stack.is(Items.LANTERN))lanterns+=stack.getCount();
            }
            check(emeralds==6&&lanterns==0,"native payment and delivery persist together in player file after process restart");
            check(politics.page(player,UUID.randomUUID(),A,"honors",0).rows().stream().filter(r->r.title().contains("workshop_service")).count()==1,"civic honor survives process restart exactly once");
            var target=kingdoms.findSettlement("R2 Neighbor").orElseThrow();
            check(SettlementKnowledge.get(server).visible(player,target.id()),"private hospitality knowledge survives restart");
            schedule.accept(80L,()->finish.accept("PASS integration R2 restart: durable native payment receipt, honor and hospitality knowledge"));return;
        }
        check(CivicConfig.INSTITUTIONAL_SERVICES.get(),"R2 enabled by default without an institutionalServices override");
        var source=CivicLoopScenario.place(server,98401,new BlockPos(6000,-60,6000),"R2 Workshop");
        var target=CivicLoopScenario.place(server,98402,new BlockPos(6200,-60,6000),"R2 Neighbor");
        kingdoms.setKingdom(target.settlement().id(),new ResourceLocation(B));
        player.moveTo(6001,-60,6000);operator.moveTo(6001,-60,6000);other.moveTo(6201,-60,6000);
        success(politics.execute(operator,request(politics,Action.BOOTSTRAP,A,A+"_charter",source.settlement().id().toString(),new Person(operator.getUUID(),Kind.PLAYER),"","",BlockPos.ZERO)));
        success(politics.execute(operator,request(politics,Action.BOOTSTRAP,B,B+"_charter",target.settlement().id().toString(),new Person(other.getUUID(),Kind.PLAYER),"","",BlockPos.ZERO)));
        schedule.accept(40L,()->{try {
            var sourceRecognition=success(politics.execute(operator,request(politics,Action.RECOGNIZE,A,"ultima_kingdoms:guild","",null,"","",source.settlement().anchor())));
            operator.moveTo(6201,-60,6000);
            var targetRecognition=success(politics.execute(other,request(politics,Action.RECOGNIZE,B,"ultima_kingdoms:guild","",null,"","",target.settlement().anchor())));
            check(civic.charter(operator,ORG,UUID.fromString(sourceRecognition.recordId())).success(),"recognized source chapter chartered");
            check(civic.charter(operator,ORG,UUID.fromString(targetRecognition.recordId())).success(),"recognized neighbor chapter chartered");
            var chapters=civic.knownChapters(operator,0);
            UUID sourceChapter=chapters.stream().filter(c->c.institution().toString().equals(sourceRecognition.recordId())).findFirst().orElseThrow().id();
            UUID targetChapter=chapters.stream().filter(c->c.institution().toString().equals(targetRecognition.recordId())).findFirst().orElseThrow().id();
            check(civic.appoint(operator,targetChapter,target.npc()).success(),"neighbor contact appointed");
            operator.moveTo(6001,-60,6000);check(civic.appoint(operator,sourceChapter,source.npc()).success(),"workshop contact appointed");
            check(!civic.hospitality(player,source.npc()).success(),"neutral hospitality denied before agreement");
            treaty(politics,operator,other,"ultima_kingdoms:diplomatic_recognition");
            UUID accord=treaty(politics,operator,other,"ultima_kingdoms:hospitality_accord");
            var intro=civic.hospitality(player,source.npc());check(intro.success(),"signed hospitality opens neutral introduction");
            check(SettlementKnowledge.get(server).visible(player,target.settlement().id())&&!SettlementKnowledge.get(server).visible(other,target.settlement().id()),"hospitality destination is requester-private");
            success(politics.execute(operator,request(politics,Action.TERMINATE,A,"",accord.toString(),null,"","",BlockPos.ZERO)));
            check(!civic.hospitality(player,source.npc()).success(),"terminated hospitality rejects cooldown replay");
            var org=OrganizationApi.get(server);
            for(var deed:org.definition(ORG).orElseThrow().deeds()) {
                org.recordDeed(player.getUUID(),ORG,UUID.randomUUID(),deed.questId().toString(),deed.credit(),Optional.of(source.settlement().id()));
                if(org.ownSnapshot(player).memberships().get(0).standing()>=60)break;
            }
            check(org.join(player,ORG).applied(),"artisan voluntarily joins guild");
            check(civic.speakerContext(player,source.npc()).orElseThrow().commissionQualified(),"member artisan qualified by fixture service");
            Codec<?> codec=(Codec<?>)Class.forName(NativeQuestScenario.PREFIX+"quest.QuestDefinition").getField("CODEC").get(null);
            Object definition=codec.parse(JsonOps.INSTANCE,JsonParser.parseString(Files.readString(Path.of("workshop_lanterns.json")))).getOrThrow(false,m->{throw new AssertionError(m);});
            NativeQuestScenario.register(definition);
            Object data=((Optional<?>)NativeQuestScenario.invoke("state.QuestCapabilities","get",player)).orElseThrow();
            check(!(boolean)NativeQuestScenario.invoke("quest.QuestManager","accept",player,source.npc(),CivicService.WORKSHOP_QUEST),"ordinary path cannot accept institutional template without binding");
            R2CrimeFixture.setup(player,source.npc());
            R2CrimeFixture.reportTheft(player,source.npc(),false);
            check(com.ultimakingdoms.compat.crime.InstitutionalCrimeBridge.workshop(player,source.npc()).allowed(),"unreported local theft does not become institutional knowledge");
            var staleOffer=civic.workshop(player,source.npc());check(staleOffer.success(),"native institutional offer opened before legal change");
            UUID firstCase=R2CrimeFixture.reportTheft(player,source.npc(),true);
            check(!(boolean)NativeQuestScenario.invoke("quest.QuestManager","accept",player,source.npc(),CivicService.WORKSHOP_QUEST),"reported case invalidates previously displayed acceptance");
            check(!civic.workshop(player,source.npc()).success(),"reported local case suspends only institutional service");
            R2CrimeFixture.resolveRestitution(player,firstCase);
            var restored=com.ultimakingdoms.compat.crime.InstitutionalCrimeBridge.workshop(player,source.npc());
            check(restored.allowed()&&!restored.restitution().isEmpty(),"Crime-owned receipt-backed amends settle local case and restore workshop");
            var open=civic.workshop(player,source.npc());check(open.success(),"recognized workshop opens paid native offer: "+open.reason());
            check((boolean)NativeQuestScenario.invoke("quest.QuestManager","accept",player,source.npc(),CivicService.WORKSHOP_QUEST),"native acceptance binds recognized workshop authorization");
            Object cancelledActive=((List<?>)NativeQuestScenario.call(data,"active")).get(0);
            int reservedBefore=civic.reservedHonorSlots(Set.of());
            check((boolean)NativeQuestScenario.invoke("quest.QuestManager","abandon",player,source.npc(),CivicService.WORKSHOP_QUEST),"native cancellation succeeds and releases owner promise");
            check(civic.reservedHonorSlots(Set.of())==reservedBefore-1,"cancellation durably releases honor reservation");
            check(!(boolean)NativeQuestScenario.invoke("quest.QuestManager","completeQuest",player,source.npc(),definition,cancelledActive,data),"cancelled instance cannot complete or pay");
            NativeQuestScenario.call(data,"add",cancelledActive); // Simulate old native playerdata after owner cancellation committed.
            player.getInventory().add(new ItemStack(Items.LANTERN,4));
            check(!(boolean)NativeQuestScenario.invoke("quest.QuestManager","completeQuest",player,source.npc(),definition,cancelledActive,data),"resurrected native active cannot complete without owner contract");
            check(player.getInventory().countItem(Items.LANTERN)==4&&player.getInventory().countItem(Items.EMERALD)==0,"cancellation crash window preserves inventory");
            check((boolean)NativeQuestScenario.invoke("quest.QuestManager","abandon",player,source.npc(),CivicService.WORKSHOP_QUEST),"cancellation retries after owner deletion with native active restored");
            player.getInventory().clearContent();
            check(civic.workshop(player,source.npc()).success(),"fresh commission available after cancellation");
            check((boolean)NativeQuestScenario.invoke("quest.QuestManager","accept",player,source.npc(),CivicService.WORKSHOP_QUEST),"fresh commission accepts after cancellation");
            Object active=((List<?>)NativeQuestScenario.call(data,"active")).get(0);
            check(org.leave(player,ORG).applied(),"guild departure preserves accepted commission");
            check(!civic.speakerContext(player,source.npc()).orElseThrow().commissionQualified(),"former member no longer qualifies for a new commission");
            player.getInventory().add(new ItemStack(Items.LANTERN,4));int emeralds=player.getInventory().countItem(Items.EMERALD);
            IntegrationScenario.call(source.building(),"setType","library");
            check(!(boolean)NativeQuestScenario.invoke("quest.QuestManager","completeQuest",player,source.npc(),definition,active,data),"changed workshop refuses native completion");
            check(player.getInventory().countItem(Items.LANTERN)==4&&player.getInventory().countItem(Items.EMERALD)==emeralds,"refused completion preserves goods and currency");
            IntegrationScenario.call(source.building(),"setType","workshop");
            UUID secondCase=R2CrimeFixture.reportTheft(player,source.npc(),true);
            check(!(boolean)NativeQuestScenario.invoke("quest.QuestManager","completeQuest",player,source.npc(),definition,active,data),"reported case suspends accepted commission before item consumption");
            check(player.getInventory().countItem(Items.LANTERN)==4&&player.getInventory().countItem(Items.EMERALD)==emeralds,"legal refusal preserves goods and money");
            R2CrimeFixture.resolveRestitution(player,secondCase);
            NativeQuestScenario.complete(player,source.npc(),definition,active,data);
            check(player.getInventory().countItem(Items.LANTERN)==0&&player.getInventory().countItem(Items.EMERALD)==emeralds+6,"native commission consumes four lanterns and pays six emeralds once");
            QuestCompletionBridge.consume(player);QuestCompletionBridge.consume(player);
            check(politics.page(player,UUID.randomUUID(),A,"honors",0).rows().stream().filter(r->r.title().contains("workshop_service")).count()==1,"durable native completion awards one civic honor across replay");
            check(!(boolean)NativeQuestScenario.invoke("quest.QuestManager","completeQuest",player,source.npc(),definition,active,data),"completion replay cannot pay twice");
            server.saveEverything(false,true,true);
            finish.accept("PASS integration R2: recognized workshop, neutral hospitality/privacy/termination, native payment, stale-building refusal, durable honor replay");
        }catch(Exception failure){throw new RuntimeException(failure);}});
    }
}
