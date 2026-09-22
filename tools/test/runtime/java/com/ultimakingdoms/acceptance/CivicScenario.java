package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.factions.*;
import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.civic.CivicViews;
import com.ultimakingdoms.compat.recruits.RecruitsObservation;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import java.util.*;
import java.util.function.Consumer;
import static com.ultimakingdoms.acceptance.IntegrationScenario.check;

/** Synthetic provider receipts verify the foundation; this does not claim a live quest-completion bridge. */
final class CivicScenario {
    static final UUID ALICE=UUID.fromString("080d0e74-1a01-488b-a231-e5e70d651a01"), BOB=UUID.fromString("080d0e74-1a01-488b-a231-e5e70d651a02");
    static final UUID RECEIPT=UUID.fromString("080d0e74-1a01-488b-a231-e5e70d651a03");
    static final ResourceLocation ORG=new ResourceLocation("ultima_kingdoms:lamplighters"), PERMISSION=new ResourceLocation("ultima_kingdoms:route_introductions");
    static final String QUEST="ultima:civic/lamplighters/charters_night";
    static void run(MinecraftServer server,String phase,Consumer<String> finish) throws Exception {
        var service=OrganizationApi.get(server); var kingdoms=UltimaKingdomsApi.get(server);
        var alice=new ServerPlayer(server,server.overworld(),new GameProfile(ALICE,"CivicAlice"));
        var bob=new ServerPlayer(server,server.overworld(),new GameProfile(BOB,"CivicBob"));
        var knowledge=SettlementKnowledge.get(server);
        check(!alice.hasPermissions(2)&&!bob.hasPermissions(2),"privacy subjects are non-operators");
        check(service.definition(ORG).isPresent(),"packaged Lamplighters definition is loaded");
        var military=RecruitsObservation.here(alice);
        check(military.status()==(ModList.get().isLoaded("recruits")?RecruitsObservation.Status.AVAILABLE:RecruitsObservation.Status.ABSENT),"optional Recruits current-location observation: "+military.status());
        if(ModList.get().isLoaded("recruits")) {
            // Fixture writes are confined to this isolated acceptance world, never the production adapter.
            Class<?> factionType=Class.forName("com.talhanation.recruits.world.RecruitsFaction");
            Object faction=factionType.getConstructor().newInstance();
            factionType.getMethod("setStringID",String.class).invoke(faction,"r1_fixture");
            Class<?> claimType=Class.forName("com.talhanation.recruits.world.RecruitsClaim");
            Object claim=claimType.getConstructor(String.class,factionType).newInstance("R1 fixture",faction);
            claimType.getMethod("setCenter",net.minecraft.world.level.ChunkPos.class).invoke(claim,alice.chunkPosition());
            claimType.getMethod("addChunk",net.minecraft.world.level.ChunkPos.class).invoke(claim,alice.chunkPosition());
            Object manager=Class.forName("com.talhanation.recruits.ClaimEvents").getField("recruitsClaimManager").get(null);
            manager.getClass().getMethod("addOrUpdateClaim",net.minecraft.server.level.ServerLevel.class,claimType).invoke(manager,server.overworld(),claim);
            var occupied=RecruitsObservation.here(alice);
            check(occupied.status()==RecruitsObservation.Status.AVAILABLE&&occupied.ownerId().equals("r1_fixture")&&!occupied.claimId().isEmpty(),"exact Recruits jar exposes current claim owner and identity through optional adapter");
            manager.getClass().getMethod("removeClaim",claimType).invoke(manager,claim);
        }
        if(phase.equals("r1-restart")) {
            var member=service.ownSnapshot(alice).memberships().get(0);
            check(member.status()==OrganizationMembershipSnapshot.Status.ACTIVE&&member.standing()==20&&member.deedCount()==1,"membership and evidence persist after process restart");
            check(service.recordDeed(ALICE,ORG,RECEIPT,QUEST,20,Optional.empty()).status()==OrganizationDeedResult.Status.REPLAYED,"receipt replay remains idempotent after process restart");
            var village=kingdoms.findSettlement("Civic Hidden Village").orElseThrow();
            check(knowledge.visible(alice,village.id())&&!knowledge.visible(bob,village.id()),"private discovery remains private after restart; adoption does not republish it");
            check(service.ownSnapshot(bob).memberships().isEmpty(),"second player's profile remains isolated after restart");
            finish.accept("PASS integration R1 restart: membership, evidence, receipt replay and private discovery"); return;
        }
        check(service.ownSnapshot(alice).memberships().isEmpty(),"new player remains unaffiliated");
        check(service.explainOwn(alice,ORG,PERMISSION).decision()==OrganizationExplanation.Decision.DENY,"unaffiliated policy denies member permission");
        check(service.recordDeed(ALICE,ORG,UUID.randomUUID(),"ultima:invented",999,Optional.empty()).status()==OrganizationDeedResult.Status.INVALID_EVIDENCE,"unknown deed and arbitrary credit denied");
        var deed=service.recordDeed(ALICE,ORG,RECEIPT,QUEST,20,Optional.empty());
        check(deed.applied()&&deed.membership().orElseThrow().status()==OrganizationMembershipSnapshot.Status.UNAFFILIATED,"verified provider evidence can acknowledge a neutral player without enrolling them");
        check(service.recordDeed(ALICE,ORG,RECEIPT,QUEST,20,Optional.empty()).status()==OrganizationDeedResult.Status.REPLAYED,"same receipt applied once");
        check(service.recordDeed(ALICE,ORG,RECEIPT,QUEST,10,Optional.empty()).status()==OrganizationDeedResult.Status.CONFLICTING_REPLAY,"changed receipt payload denied");
        check(service.recordDeed(ALICE,ORG,UUID.randomUUID(),QUEST,20,Optional.empty()).status()==OrganizationDeedResult.Status.INVALID_EVIDENCE,"fresh receipt cannot farm a once-per-player quest");
        check(service.join(alice,ORG).applied(),"voluntary guild join succeeds");
        check(service.explainOwn(alice,ORG,PERMISSION).decision()==OrganizationExplanation.Decision.ALLOW,"policy explains qualified member access without invoking a service");
        check(service.leave(alice,ORG).applied(),"member may leave");
        check(service.explainOwn(alice,ORG,PERMISSION).decision()==OrganizationExplanation.Decision.DENY,"leaving removes permissions immediately");
        check(service.join(alice,ORG).applied()&&service.ownSnapshot(alice).memberships().get(0).standing()==20,"rejoining retains evidence without duplication");
        check(service.ownSnapshot(bob).memberships().isEmpty(),"own-player API never includes another player's membership");
        var faction=UltimaFactionsApi.get(server);
        check(faction.getStanding(ALICE,new ResourceLocation("ultima_kingdoms:serenum")).isEmpty(),"guild actions never write legacy kingdom standing");
        var village=kingdoms.registerCandidate(server.overworld(),SettlementCandidate.manual(server.overworld().dimension(),new BlockPos(9000,65,9000),32,new ResourceLocation("ultima_acceptance:r1"),"civic-hidden","Civic Hidden Village"));
        check(!knowledge.visible(alice,village.id())&&!knowledge.visible(bob,village.id()),"new settlement begins undiscovered for both players");
        knowledge.discover(ALICE,village.id());
        check(knowledge.page(alice,kingdoms,Optional.empty(),0,64).stream().anyMatch(v -> v.id().equals(village.id())),"discoverer can list settlement");
        check(knowledge.page(bob,kingdoms,Optional.empty(),0,64).stream().noneMatch(v -> v.id().equals(village.id()))&&knowledge.find(bob,kingdoms,village.id().toString()).isEmpty()&&knowledge.find(bob,kingdoms,village.displayName()).isEmpty(),"other player cannot list or resolve guessed UUID/name");
        var suggestions=server.getCommands().getDispatcher().getCompletionSuggestions(server.getCommands().getDispatcher().parse("ultima village info ",bob.createCommandSourceStack())).get();
        check(suggestions.getList().stream().noneMatch(s -> s.getText().contains("Civic")||s.getText().contains(village.slug().getPath())),"command suggestions hide undiscovered settlement");
        check(CivicViews.own(alice,0).active()&&!CivicViews.own(bob,0).active(),"ledger read model is caller-specific");
        Throwable[] offThread={null}; Thread thread=new Thread(() -> { try {service.ownSnapshot(alice);} catch(Throwable failure){offThread[0]=failure;} });thread.start();thread.join();
        check(offThread[0] instanceof IllegalStateException,"off-thread organization query rejected");
        CivicPrivacyFixture.assertMerge(server,alice,bob,village);
        server.saveEverything(false,true,true);
        finish.accept("PASS integration R1 foundation: voluntary membership, isolated standing, durable receipts, policy, discovery and optional provider observation");
    }
}
