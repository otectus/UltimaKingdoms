package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.api.politics.PoliticalTransition.*;
import com.ultimakingdoms.compat.recruits.RecruitsMilitary;
import com.ultimakingdoms.evolution.EvolutionRuntime;
import com.ultimakingdoms.evolution.drama.*;
import com.ultimakingdoms.factions.organization.OrganizationRuntime;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.politics.GovernmentService;
import com.ultimakingdoms.warfare.*;
import com.ultimakingdoms.warfare.CampaignState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

import static com.ultimakingdoms.acceptance.IntegrationScenario.call;
import static com.ultimakingdoms.acceptance.IntegrationScenario.check;

/** Fresh-world packaged acceptance for R4 political extensions and their real owner APIs. */
final class R4PoliticalExtensionsScenario {
    private static final String A="ultima_kingdoms:serenum", B="ultima_kingdoms:lunari";
    private static final ResourceLocation TEMPLATE=new ResourceLocation("ultima_kingdoms:lamplighters");

    @SuppressWarnings("unchecked")
    static void run(MinecraftServer server, Consumer<String> finish) throws Exception {
        var level=server.overworld();var kingdoms=UltimaKingdomsApi.get(server);var politics=UltimaPoliticsApi.get(server);
        var one=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4ExtensionsLeader")){@Override public boolean hasPermissions(int n){return n<=2;}};
        var two=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4ExtensionsNeighbor"));
        var member=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4ExtensionsMember"));
        var outsider=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"R4ExtensionsOutsider"));
        List<ServerPlayer> players=List.of(one,two,member,outsider);for(int i=0;i<players.size();i++){players.get(i).setPos(i,64,1);server.getProfileCache().add(players.get(i).getGameProfile());}
        Map<UUID,ServerPlayer> online=null;
        for(var field:PlayerList.class.getDeclaredFields())if(field.getGenericType().getTypeName().contains("java.util.UUID")&&field.getGenericType().getTypeName().contains("ServerPlayer")){
            field.setAccessible(true);online=(Map<UUID,ServerPlayer>)field.get(server.getPlayerList());break;}
        check(online!=null,"R4 extensions fixture locates authenticated online registry");for(var player:players)online.put(player.getUUID(),player);
        try{
            var seatA=MilitaryScenario.settlement(kingdoms,level,new BlockPos(0,64,0),new ResourceLocation(A),"R4 Extensions Assembly");
            var seatB=MilitaryScenario.settlement(kingdoms,level,new BlockPos(160,64,0),new ResourceLocation(B),"R4 Extensions Neighbor");
            var target=MilitaryScenario.settlement(kingdoms,level,new BlockPos(320,64,0),new ResourceLocation(B),"R4 Extensions Frontier");
            success(politics.execute(one,request(politics,Action.BOOTSTRAP,A,A+"_charter",seatA.id().toString(),one)));
            success(politics.execute(one,request(politics,Action.BOOTSTRAP,B,B+"_charter",seatB.id().toString(),two)));
            for(var player:players)for(var settlement:List.of(seatA,seatB,target))SettlementKnowledge.get(server).discover(player.getUUID(),settlement.id());

            electionsAndRegency(server,politics,one,two,member,outsider);
            organizations(server,politics,one,two,member,outsider);
            dramaAndCampaign(server,kingdoms,politics,one,two,member,target,seatA);

            server.saveEverything(false,true,true);
            var data=server.getWorldPath(LevelResource.ROOT).resolve("data");
            check(Files.size(data.resolve("ultima_kingdoms_politics.dat"))>0&&Files.size(data.resolve("ultima_kingdoms_organizations.dat"))>0
                    &&Files.size(data.resolve("ultima_kingdoms_evolution_drama.dat"))>0,"R4 extension owner ledgers are durably present in the packaged world");
            finish.accept("PASS integration R4 extensions: explicit elections/regency, durable lifecycle blockers and opt-outs, voluntary schism, provider-confirmed campaign");
        }finally{for(var player:players)online.remove(player.getUUID());}
    }

    private static void electionsAndRegency(MinecraftServer server,PoliticalService politics,ServerPlayer one,ServerPlayer two,
                                             ServerPlayer member,ServerPlayer outsider)throws Exception{
        var rule=new Rule(true,true,1200,200,1200,4,Set.of(Permission.APPOINT,Permission.SEAT));UUID ruleRequest=UUID.randomUUID();
        var denied=politics.adoptTransitionRule(outsider,UUID.randomUUID(),politics.revision(),A,rule);
        check(!denied.success()&&politics.transitionRule(A).isEmpty(),"non-leader cannot opt a government into constitutional transitions");
        success(politics.adoptTransitionRule(one,ruleRequest,politics.revision(),A,rule));
        check(politics.receipt(one,ruleRequest).orElseThrow().success()&&politics.transitionRule(A).orElseThrow().equals(rule),"leader opt-in is durably receipted with frozen constitutional limits");
        success(politics.execute(one,new Request(UUID.randomUUID(),politics.revision(),Action.DELEGATE,A,Permission.APPOINT.name(),"",
                new Person(two.getUUID(),Kind.PLAYER),"","","",0,0,0)));

        UUID electionRequest=UUID.randomUUID();success(politics.openElection(one,electionRequest,politics.revision(),A,List.of(member.getUUID(),outsider.getUUID())));
        UUID election=UUID.fromString(politics.receipt(one,electionRequest).orElseThrow().recordId());
        check(politics.election(outsider,election).isEmpty(),"candidate without electorate or appointment authority cannot inspect private ballot progress");
        success(politics.castBallot(one,UUID.randomUUID(),politics.revision(),election,member.getUUID()));
        success(politics.castBallot(two,UUID.randomUUID(),politics.revision(),election,outsider.getUUID()));
        success(politics.closeElection(one,UUID.randomUUID(),politics.revision(),election));
        check(politics.election(one,election).orElseThrow().state()==ElectionState.GRACE,"equal authenticated ballots enter neutral grace without choosing a winner");
        success(politics.castBallot(one,UUID.randomUUID(),politics.revision(),election,outsider.getUUID()));
        success(politics.closeElection(one,UUID.randomUUID(),politics.revision(),election));
        var resolved=politics.election(one,election).orElseThrow();
        check(resolved.state()==ElectionState.RESOLVED&&resolved.winner().orElseThrow().equals(outsider.getUUID())
                &&resolved.ballotsCast()==2,"changed ballot resolves deterministically to one candidate without exposing ballot identities");
        check(politics.government(A).orElseThrow().successor().id().equals(outsider.getUUID()),"active-government election installs exactly the named lawful successor");

        success(politics.appointRegent(one,UUID.randomUUID(),politics.revision(),A,outsider.getUUID()));
        var regency=politics.regency(A).orElseThrow();check(regency.active()&&regency.preservedSuccessor().id().equals(outsider.getUUID()),"regency freezes the elected named successor");
        check(politics.authorized(outsider,A,Permission.APPOINT)&&!politics.authorized(outsider,A,Permission.RATIFY),"regent receives only enumerated constitutional permissions");
        var replace=politics.execute(outsider,new Request(UUID.randomUUID(),politics.revision(),Action.NAME_SUCCESSOR,A,"","",new Person(member.getUUID(),Kind.PLAYER),"","","",0,0,0));
        check(!replace.success()&&politics.government(A).orElseThrow().successor().id().equals(outsider.getUUID()),"regent cannot overwrite the preserved successor");
        server.getWorldData().overworldData().setGameTime(regency.expiresAt()+1);
        var maintenance=GovernmentService.class.getDeclaredMethod("maintainDeadlines");maintenance.setAccessible(true);maintenance.invoke(politics);
        check(!politics.regency(A).orElseThrow().active()&&politics.government(A).orElseThrow().successor().id().equals(outsider.getUUID()),"regency expiry durably removes interim authority and preserves succession");
    }

    private static void organizations(MinecraftServer server,PoliticalService politics,ServerPlayer one,ServerPlayer two,
                                      ServerPlayer member,ServerPlayer outsider)throws Exception{
        var organizations=OrganizationRuntime.get(server);var source=new ResourceLocation("ultima_acceptance:r4_source");
        var target=new ResourceLocation("ultima_acceptance:r4_target");var archive=new ResourceLocation("ultima_acceptance:r4_archive");
        applied(organizations.found(one,source,TEMPLATE,A,"R4 Source Fellowship",organizations.revision()));
        applied(organizations.found(two,target,TEMPLATE,B,"R4 Target Fellowship",organizations.revision()));
        check(organizations.join(member,source).applied(),"source member joins through the real organization owner API");
        long memberStanding=organizations.ownSnapshot(member).memberships().stream().filter(m->m.organizationId().equals(source)).findFirst().orElseThrow().standing();
        var proposed=organizations.proposeMerge(one,source,target,organizations.revision());
        check(proposed.status()==OrganizationLifecycleResult.Status.PENDING_CONSENT,"source authority creates only a pending bilateral merge");UUID merge=proposed.merge().orElseThrow().id();
        check(organizations.consentMerge(outsider,merge,organizations.revision()).status()==OrganizationLifecycleResult.Status.UNAUTHORIZED,"unrelated player cannot supply target consent");
        applied(organizations.consentMerge(two,merge,organizations.revision()));applied(organizations.optOutMerge(member,merge,organizations.revision()));

        UUID obligation=acceptedCommission(server,politics,member,source);
        var blocked=organizations.finalizeMerge(one,merge,organizations.revision());
        check(blocked.status()==OrganizationLifecycleResult.Status.OBLIGATIONS_BLOCKING&&blocked.obligations().stream().anyMatch(o->o.id().equals(obligation)),"accepted native commission durably blocks lifecycle finalization");
        applied(organizations.novateMergeObligation(one,merge,obligation,organizations.revision()));applied(organizations.finalizeMerge(one,merge,organizations.revision()));
        check(organizations.resolve(source).orElseThrow().equals(target)&&organizations.lifecycle(source).orElseThrow().state()==OrganizationLifecycleView.State.MERGED,"applied merge keeps a stable historical redirect and tombstone");
        var opted=organizations.ownSnapshot(member).memberships().stream().filter(m->m.organizationId().equals(source)).findFirst().orElseThrow();
        check(opted.status()==OrganizationMembershipSnapshot.Status.LEFT&&opted.standing()==memberStanding
                &&organizations.ownSnapshot(member).memberships().stream().noneMatch(m->m.organizationId().equals(target)),
                "member opt-out preserves historical standing and refuses target enrollment");

        applied(organizations.found(one,archive,TEMPLATE,A,"R4 Archive Fellowship",organizations.revision()));
        applied(organizations.dissolve(one,archive,organizations.revision()));
        check(organizations.lifecycle(archive).orElseThrow().state()==OrganizationLifecycleView.State.DISSOLVED,"explicit dissolution leaves a durable lifecycle tombstone");
    }

    private static void dramaAndCampaign(MinecraftServer server,KingdomsService kingdoms,PoliticalService politics,ServerPlayer one,
                                         ServerPlayer two,ServerPlayer member,SettlementView target,SettlementView seatA)throws Exception{
        EvolutionRuntime.get(server).configure(one,true,true);var organizations=OrganizationRuntime.get(server);
        check(organizations.join(one,TEMPLATE).applied()&&organizations.join(two,TEMPLATE).applied(),"schism signatories hold real source organization memberships");
        var drama=DramaRuntime.get(server);UUID schism=UUID.randomUUID();
        drama.propose(one,schism,drama.revision(),"ultima_kingdoms:concord_schism",seatA.id(),TEMPLATE.toString());
        boolean refused=false;try{drama.consent(member,schism,1);}catch(IllegalArgumentException expected){refused=true;}
        check(refused,"non-member cannot consent to a peaceful organizational schism");
        drama.consent(two,schism,1);var ready=drama.drama(schism).orElseThrow();drama.execute(one,schism,ready.revision());
        var resolved=drama.drama(schism).orElseThrow();var newOrganization=new ResourceLocation(resolved.dynamicOrganization());
        check(resolved.phase()==DramaState.Phase.RESOLVED&&organizations.lifecycle(newOrganization).orElseThrow().state()==OrganizationLifecycleView.State.ACTIVE,"schism founds an explicit charter without rewriting faith or civic identity");
        check(organizations.ownSnapshot(two).memberships().stream().noneMatch(m->m.organizationId().equals(newOrganization)),"consent does not silently move the second member");
        check(organizations.join(two,newOrganization).applied(),"second schism participant chooses new membership explicitly");

        nativeCampaign(server,kingdoms,politics,one,two,target);
        UUID campaignDrama=UUID.randomUUID();drama.propose(one,campaignDrama,drama.revision(),"ultima_kingdoms:border_campaign",target.id(),"");
        drama.consent(two,campaignDrama,1);var campaignReady=drama.drama(campaignDrama).orElseThrow();
        String outcome=drama.execute(one,campaignDrama,campaignReady.revision());var campaignState=drama.drama(campaignDrama).orElseThrow();
        var nativeCampaign=CampaignService.get(server).campaign(campaignState.providerRequest()).orElseThrow();
        check(Set.of(CampaignState.Phase.NOTICE,CampaignState.Phase.ACTIVE).contains(nativeCampaign.phase())
                &&RecruitsMilitary.saved(server,"r4x_a","r4x_b",CampaignState.Relation.ENEMY),"authored campaign uses the real native declaration and saved acknowledgment: "+outcome);
        check(kingdoms.getSettlement(target.id()).orElseThrow().kingdomId().toString().equals(B),"campaign declaration preserves target civic identity and assets");
    }

    private static void nativeCampaign(MinecraftServer server,KingdomsService kingdoms,PoliticalService politics,ServerPlayer one,
                                       ServerPlayer two,SettlementView target)throws Exception{
        var level=server.overworld();Object factions=Class.forName("com.talhanation.recruits.FactionEvents").getField("recruitsFactionManager").get(null);
        call(factions,"addTeam","r4x_a","r4x_a",one.getUUID(),one.getName().getString(),new CompoundTag(),(byte)1,net.minecraft.ChatFormatting.BLUE);
        call(factions,"addTeam","r4x_b","r4x_b",two.getUUID(),two.getName().getString(),new CompoundTag(),(byte)1,net.minecraft.ChatFormatting.RED);
        for(var player:List.of(one,two)){String name=player==one?"r4x_a":"r4x_b";var team=server.getScoreboard().getPlayerTeam(name);if(team==null)team=server.getScoreboard().addPlayerTeam(name);server.getScoreboard().addPlayerToTeam(player.getScoreboardName(),team);}
        Object manager=Class.forName("com.talhanation.recruits.ClaimEvents").getField("recruitsClaimManager").get(null);Class<?> type=Class.forName("com.talhanation.recruits.world.RecruitsClaim");
        Object own=type.getConstructor(String.class,call(factions,"getFactionByStringID","r4x_a").getClass()).newInstance("R4X A",call(factions,"getFactionByStringID","r4x_a"));
        call(own,"setCenter",new ChunkPos(0,0));call(own,"addChunk",new ChunkPos(0,0));call(manager,"addOrUpdateClaim",level,own);
        Object defending=type.getConstructor(String.class,call(factions,"getFactionByStringID","r4x_b").getClass()).newInstance("R4X Frontier",call(factions,"getFactionByStringID","r4x_b"));
        ChunkPos chunk=new ChunkPos(target.anchor());call(defending,"setCenter",chunk);call(defending,"addChunk",chunk);call(manager,"addOrUpdateClaim",level,defending);
        var control=WarfareRuntime.get(server);one.setPos(target.anchor().getX(),64,target.anchor().getZ());control.mapHere(one,new ResourceLocation(B));
        one.setPos(1,64,1);control.mapHere(one,new ResourceLocation(A));call(manager,"save",level);call(factions,"save",level);server.saveEverything(false,true,true);
        one.setPos(target.anchor().getX(),64,target.anchor().getZ());control.bindHere(one,target.id());
        check(control.verifiedBinding(target.id()).isPresent(),"authored campaign fixture has a provider-saved native claim binding");
    }

    private static UUID acceptedCommission(MinecraftServer server,PoliticalService politics,ServerPlayer member,ResourceLocation organization)throws Exception{
        Class<?> dataType=Class.forName("com.ultimakingdoms.civic.InstitutionalCommissionData");var get=dataType.getDeclaredMethod("get",MinecraftServer.class);get.setAccessible(true);Object data=get.invoke(null,server);
        Class<?> contractType=Class.forName("com.ultimakingdoms.civic.InstitutionalCommissionData$Contract");Constructor<?> constructor=contractType.getDeclaredConstructors()[0];constructor.setAccessible(true);
        UUID id=UUID.randomUUID(),instance=UUID.randomUUID();Definition honor=com.ultimakingdoms.UltimaKingdoms.POLITICS.get("ultima_kingdoms:workshop_service","honor");
        Object contract=constructor.newInstance(id,member.getUUID(),UUID.randomUUID(),UUID.randomUUID(),organization.toString(),A,
                "ultima:civic/lamplighters/workshop_lanterns",politics.revision(),0L,"r4-fixture-building","r4-fixture-law",
                server.overworld().getGameTime()+1200,instance,"ultima_kingdoms:workshop_service",honor);
        var put=dataType.getDeclaredMethod("put",MinecraftServer.class,contractType);put.setAccessible(true);check((boolean)put.invoke(data,server,contract),"accepted commission fixture commits through durable civic ledger");return id;
    }

    private static Request request(PoliticalService politics,Action action,String kingdom,String definition,String target,ServerPlayer person){
        return new Request(UUID.randomUUID(),politics.revision(),action,kingdom,definition,target,new Person(person.getUUID(),Kind.PLAYER),"","","",0,64,0);
    }
    private static void success(Result result){check(result.success(),"political transition: "+result.message());}
    private static void applied(OrganizationLifecycleResult result){check(result.status()==OrganizationLifecycleResult.Status.APPLIED,result.reason());}
    private R4PoliticalExtensionsScenario(){ }
}
