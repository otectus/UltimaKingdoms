package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.compat.recruits.*;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.*;
import com.ultimakingdoms.warfare.CampaignState.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.util.FakePlayer;
import java.util.*;
import java.util.function.Consumer;
import static com.ultimakingdoms.acceptance.IntegrationScenario.*;

/** Isolated exact-provider authority, persistence and recovery checks. */
final class MilitaryScenario {
    @SuppressWarnings("unchecked")
    static void run(MinecraftServer server, Consumer<String> finish) throws Exception {
        var level = server.overworld(); var kingdoms = UltimaKingdomsApi.get(server); var politics = UltimaPoliticsApi.get(server);
        var one = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "MilitaryOne")) { @Override public boolean hasPermissions(int n) { return n <= 2; } };
        var two = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "MilitaryTwo"));
        var civilian = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "Civilian"));
        one.setPos(0,64,0); two.setPos(0,64,0); civilian.setPos(0,64,0);
        server.getProfileCache().add(one.getGameProfile()); server.getProfileCache().add(two.getGameProfile());
        Map<UUID,ServerPlayer> online = null;
        for (var field : PlayerList.class.getDeclaredFields()) if (field.getGenericType().getTypeName().contains("java.util.UUID") && field.getGenericType().getTypeName().contains("ServerPlayer")) {
            field.setAccessible(true); online = (Map<UUID,ServerPlayer>)field.get(server.getPlayerList()); break;
        }
        check(online != null, "test fixture locates native online player registry");
        online.put(one.getUUID(), one); online.put(two.getUUID(), two);
        try {
            check(WarfareConfig.ENABLED.get() && WarfareConfig.MILITARY.get() && WarfareConfig.CONTRACTS.get()
                    && WarfareConfig.TARGET_POLICY.get() && WarfareConfig.WORLD_CONTEXT.get(), "all R3 feature switches default enabled");
            check(RecruitsMilitary.packetGuardInstalled() && RecruitsEvents.available(), "native packet authorization and siege policy hooks installed");
            var a = new ResourceLocation("ultima_kingdoms:serenum"); var b = new ResourceLocation("ultima_kingdoms:lunari");
            var seatA = settlement(kingdoms, level, new BlockPos(160,64,0), a, "Military Capital A");
            var seatB = settlement(kingdoms, level, new BlockPos(320,64,0), b, "Military Capital B");
            var target = settlement(kingdoms, level, new BlockPos(0,64,0), b, "Military Harbor");
            for (var seat : List.of(seatA, seatB)) {
                var k = seat.kingdomId().toString(); var leader = seat == seatA ? one : two;
                var result = politics.execute(one, new Politics.Request(UUID.randomUUID(), politics.revision(), Politics.Action.BOOTSTRAP, k,
                        k + "_charter", seat.id().toString(), new Politics.Person(leader.getUUID(), Politics.Kind.PLAYER), "", "", "", 0,64,0));
                check(result.success(), "political government bootstrap: " + result.message());
            }
            Object factions = Class.forName("com.talhanation.recruits.FactionEvents").getField("recruitsFactionManager").get(null);
            call(factions, "addTeam", "mil_a", "mil_a", one.getUUID(), one.getName().getString(), new CompoundTag(), (byte)1, net.minecraft.ChatFormatting.BLUE);
            call(factions, "addTeam", "mil_b", "mil_b", two.getUUID(), two.getName().getString(), new CompoundTag(), (byte)1, net.minecraft.ChatFormatting.RED);
            for (var player : List.of(one,two)) {
                String name = player == one ? "mil_a" : "mil_b";
                var team = server.getScoreboard().getPlayerTeam(name); if (team == null) team = server.getScoreboard().addPlayerTeam(name);
                server.getScoreboard().addPlayerToTeam(player.getScoreboardName(), team);
            }
            Object owner = call(factions, "getFactionByStringID", "mil_b");
            Object manager = Class.forName("com.talhanation.recruits.ClaimEvents").getField("recruitsClaimManager").get(null);
            Class<?> type = Class.forName("com.talhanation.recruits.world.RecruitsClaim");
            Object claim = type.getConstructor(String.class, owner.getClass()).newInstance("Military Harbor", owner);
            call(claim, "setCenter", new ChunkPos(0,0)); call(claim, "addChunk", new ChunkPos(0,0)); call(manager,"addOrUpdateClaim",level,claim);
            Object ownClaim = type.getConstructor(String.class, owner.getClass()).newInstance("Military A",call(factions,"getFactionByStringID","mil_a"));
            call(ownClaim,"setCenter",new ChunkPos(10,0)); call(ownClaim,"addChunk",new ChunkPos(10,0));call(manager,"addOrUpdateClaim",level,ownClaim);
            var control = WarfareRuntime.get(server); control.mapHere(one,b); one.setPos(160,64,0);control.mapHere(one,a);one.setPos(0,64,0);
            call(manager,"save",level);call(factions,"save",level);server.saveEverything(false,true,true);control.bindHere(one,target.id());
            for(var player:List.of(one,two,civilian))SettlementKnowledge.get(server).discover(player.getUUID(),target.id());
            var campaigns = CampaignService.get(server); UUID campaign = UUID.randomUUID();
            boolean refused=false;try{campaigns.declare(civilian,UUID.randomUUID(),campaigns.revision(),target.id(),Goal.RELIEF_ACCESS,"Aid access");}catch(IllegalArgumentException e){refused=true;}
            check(refused,"civilian cannot declare a native military policy");
            long rev=campaigns.revision();wizard(one,"warfare.declare","Relief access","Military Harbor","Aid access");campaign=campaigns.campaigns(one,0,64).get(0).id();
            check(RecruitsMilitary.relation(server,"mil_a","mil_b")==Relation.ENEMY && RecruitsMilitary.saved(server,"mil_a","mil_b",Relation.ENEMY),"declaration acknowledged in native memory and save");
            check(RecruitsMilitary.relation(server,"mil_b","mil_a")==Relation.NEUTRAL,"declaration preserves counterpart direction");
            long after=campaigns.revision();campaigns.declare(one,campaign,rev,target.id(),Goal.RELIEF_ACCESS,"Aid access");check(after==campaigns.revision(),"request replay does not repeat relation write");
            UUID claimId=(UUID)call(claim,"getUUID");check(!campaigns.siegeAllowed(claimId,List.of("mil_a")),"notice prevents premature siege");
            level.getChunk(0,0);
            var troops = new ArrayList<net.minecraft.world.entity.Mob>();
            var nativeConfig = Class.forName("com.talhanation.recruits.config.RecruitsServerConfig");
            int minimum = (Integer)call(nativeConfig.getField("SiegeClaimsRecruitsAmount").get(null), "get");
            Object groups = Class.forName("com.talhanation.recruits.RecruitEvents").getField("recruitsGroupsManager").get(null);
            Object nativeGroup = ((List<?>)call(groups,"getPlayerGroups",one)).get(0);
            UUID nativeGroupId = (UUID)call(nativeGroup,"getUUID");
            for (int i=0; i<minimum+1; i++) {
                var unit = (net.minecraft.world.entity.Mob)net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits:recruit")).create(level);
                unit.setPos(3+i%6,64,3+i/6); unit.setNoAi(true); call(unit,"setOwnerUUID",Optional.of(one.getUUID())); call(unit,"setIsOwned",true);
                call(unit,"setGroupUUID",nativeGroupId);call(groups,"addMember",nativeGroupId,unit.getUUID(),level);level.addFreshEntity(unit);
                server.getScoreboard().addPlayerToTeam(unit.getScoreboardName(),server.getScoreboard().getPlayerTeam("mil_a")); troops.add(unit);
            }
            check(call(groups,"getGroup",nativeGroupId)==nativeGroup&&((List<?>)nativeGroup.getClass().getField("members").get(nativeGroup)).contains(troops.get(0).getUUID()),
                    "mobilization fixture uses a durable native group membership");
            var firstTroop=troops.get(0);firstTroop.setCustomName(net.minecraft.network.chat.Component.literal("Harbor Guard"));var beforeOrders=RecruitsMobilization.snapshot(firstTroop);
            var muster=com.ultimakingdoms.warfare.mobilization.MobilizationEvents.get(server);
            String musterResult=wizard(one,"warfare.muster","Harbor Guard","Defense");
            check(musterResult.startsWith("Mobilized"),"native defense muster applied: "+musterResult);
            UUID lease=UUID.fromString(muster.status(one).get(0).split(" ")[0]);muster.dismiss(one,lease);
            check(RecruitsMobilization.snapshot(firstTroop).equals(beforeOrders),"dismissal restores native orders and owner/group identity");
            var protectedVillager=net.minecraft.world.entity.EntityType.VILLAGER.create(level);protectedVillager.setPos(10,64,10);
            server.getScoreboard().addPlayerToTeam(protectedVillager.getScoreboardName(),server.getScoreboard().getPlayerTeam("mil_b"));
            check(!MilitaryTargetPolicy.allowed(firstTroop,protectedVillager),"enemy-team civilian remains protected by local law");
            Object nativeEvents=Class.forName("com.talhanation.recruits.ClaimEvents").getConstructor().newInstance();
            var detection=nativeEvents.getClass().getDeclaredMethod("tickDetection",net.minecraft.server.level.ServerLevel.class); detection.setAccessible(true);
            var nativeTick=nativeEvents.getClass().getDeclaredMethod("tickActiveSieges",net.minecraft.server.level.ServerLevel.class); nativeTick.setAccessible(true);
            detection.invoke(nativeEvents,level);
            check(!(boolean)call(manager,"isActiveSiege",claim),"cancelled notice Start cannot leave a hidden active siege");

            server.getWorldData().overworldData().setGameTime(level.getGameTime()+WarfareConfig.NOTICE.get()+1);campaigns.tick();
            check(campaigns.siegeAllowed(claimId,List.of("mil_a")),"announced campaign permits native detection when defender online");
            online.remove(two.getUUID());check(!campaigns.siegeAllowed(claimId,List.of("mil_a")),"offline defender policy pauses siege");online.put(two.getUUID(),two);
            var forced=Set.copyOf(level.getForcedChunks());
            detection.invoke(nativeEvents,level);
            check((boolean)call(manager,"isActiveSiege",claim) && (boolean)type.getField("isUnderSiege").get(claim),"native troop detection starts eligible siege");
            call(manager,"save",level);server.saveEverything(false,true,true);control.reconcile();control.reconcile();
            check(control.view(one,target.id()).stream().anyMatch(v->v.contains("UNDER_SIEGE")),"native started siege recorded after provider save");
            int health=(Integer)call(claim,"getHealth");online.remove(two.getUUID());nativeTick.invoke(nativeEvents,level);
            check((Integer)call(claim,"getHealth")==health,"offline defender freezes native siege damage");online.put(two.getUUID(),two);
            online.remove(two.getUUID());call(claim,"setHealth",0);nativeTick.invoke(nativeEvents,level);nativeTick.invoke(nativeEvents,level);
            check((boolean)call(manager,"isActiveSiege",claim)&&(boolean)type.getField("isUnderSiege").get(claim)
                    &&call(claim,"getOwnerFactionStringID").equals("mil_b")&&(Integer)call(claim,"getHealth")==0,
                    "denied zero-health siege retains active state without completion cleanup or re-registration");
            call(claim,"setHealth",health);online.put(two.getUUID(),two);
            // Exercise provider damage/success path; no direct owner or setSiegeSuccess fixture write.
            int ticks=0;while((boolean)call(manager,"isActiveSiege",claim)&&ticks++<10000)nativeTick.invoke(nativeEvents,level);
            check(call(claim,"getOwnerFactionStringID").equals("mil_a"),"native siege damage and completion transfer ownership");
            call(manager,"save",level);server.saveEverything(false,true,true);control.reconcile();control.reconcile();
            long occupied=control.revision();control.reconcile();control.reconcile();check(occupied==control.revision(),"one provider siege yields one occupation transition");
            firstTroop.setPersistenceRequired();firstTroop.setNoGravity(true);
            troops.stream().skip(1).forEach(net.minecraft.world.entity.Entity::discard);

            UUID accord=UUID.randomUUID();campaigns.propose(one,accord,campaigns.revision(),target.id(),"mil_b",Relation.NEUTRAL,Sovereignty.AUTONOMOUS,b.toString(),"Local autonomy and open relief");
            check(!campaigns.safeConduct(civilian,target.id()),"one signature does not invent peace");
            campaigns.sign(two,accord,campaigns.revision());
            check(RecruitsMilitary.saved(server,"mil_a","mil_b",Relation.NEUTRAL)&&RecruitsMilitary.saved(server,"mil_b","mil_a",Relation.NEUTRAL),"two native directions acknowledged before peace");
            check(campaigns.control(target.id()).orElseThrow().autonomy().equals("AUTONOMOUS"),"ratified autonomy separate from civic identity");
            check(kingdoms.getSettlement(target.id()).orElseThrow().kingdomId().equals(b),"autonomy preserves original civic kingdom");
            check(campaigns.safeConduct(civilian,target.id())&&!campaigns.siegeAllowed(claimId,List.of("mil_a")),"operational peace enables civilian relief and prevents siege");
            RecruitsMilitary.apply(one,"mil_a","mil_b",Relation.NEUTRAL,Relation.ENEMY);campaigns.tick();
            check(!campaigns.safeConduct(civilian,target.id()),"native divergence suspends accord effects without writeback");
            check(RecruitsMilitary.relation(server,"mil_a","mil_b")==Relation.ENEMY,"no relation oscillation after native divergence");
            check(forced.equals(Set.copyOf(level.getForcedChunks())),"military policy introduces no forced chunks");
            RecruitsMilitary.apply(one,"mil_a","mil_b",Relation.ENEMY,Relation.ALLY);
            var arrow=new net.minecraft.world.entity.projectile.Arrow(level,one);
            var cloud=new net.minecraft.world.entity.AreaEffectCloud(level,0,64,0);cloud.setOwner(one);
            var summon=net.minecraft.world.entity.EntityType.WOLF.create(level);summon.setTame(true);summon.setOwnerUUID(one.getUUID());
            var mount=net.minecraft.world.entity.EntityType.HORSE.create(level);mount.setOwnerUUID(two.getUUID());
            check(!MilitaryTargetPolicy.allowed(arrow,two)&&!MilitaryTargetPolicy.allowed(cloud,two)&&!MilitaryTargetPolicy.allowed(summon,two)
                    &&!MilitaryTargetPolicy.allowed(one,mount),"allied projectile, AoE, summon and mount attribution protected");
            var attack=new net.minecraftforge.event.entity.living.LivingAttackEvent(two,one.damageSources().playerAttack(one),2);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(attack);check(attack.isCanceled(),"actual attack event rejects allied sweeping/melee damage");
            var fixture=new com.google.gson.JsonObject();fixture.addProperty("actor",one.getUUID().toString());fixture.addProperty("unit",firstTroop.getUUID().toString());
            fixture.addProperty("settlement",target.id().toString());fixture.addProperty("lease",lease.toString());fixture.add("orders",new com.google.gson.Gson().toJsonTree(beforeOrders));
            java.nio.file.Files.writeString(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("r3-military-fixture.json"),fixture.toString());
            server.saveEverything(false,true,true);finish.accept("PASS integration military defaults, authority, native acknowledgments, notice, offline policy, bilateral autonomy, divergence and chunk invariants");
        } finally { online.remove(one.getUUID()); online.remove(two.getUUID()); }
    }
    private static String wizard(ServerPlayer player,String task,String...answers){
        var none=com.ultimakingdoms.interaction.InteractionNetwork.NONE;
        var reply=com.ultimakingdoms.interaction.InteractionNetwork.handle(player,new com.ultimakingdoms.interaction.InteractionNetwork.Request(UUID.randomUUID(),none,none,"TASK",task,"",0));
        for(String answer:answers){
            String operation=reply.mode().equals("choice")?"PICK":"NEXT",value=answer;
            if(operation.equals("PICK"))value=reply.options().stream().filter(o->o.label().equalsIgnoreCase(answer)).findFirst().orElseThrow(()->new AssertionError("Named GUI choice missing: "+answer)).key();
            reply=com.ultimakingdoms.interaction.InteractionNetwork.handle(player,new com.ultimakingdoms.interaction.InteractionNetwork.Request(UUID.randomUUID(),reply.session(),reply.state(),operation,value,"",0));
        }
        check(reply.mode().equals("review"),"native task reaches review without commands: "+task+" "+reply.detail());
        var request=new com.ultimakingdoms.interaction.InteractionNetwork.Request(UUID.randomUUID(),reply.session(),reply.state(),"APPLY","","",0);
        reply=com.ultimakingdoms.interaction.InteractionNetwork.handle(player,request);
        check(reply.mode().equals("result")&&!reply.detail().contains("Could not complete"),"native GUI task applies: "+task+" "+reply.detail());
        var duplicate=com.ultimakingdoms.interaction.InteractionNetwork.handle(player,request);
        check(duplicate.detail().equals(reply.detail()),"native GUI repeat click returns retained result: "+task);
        return reply.detail();
    }
    static void restart(MinecraftServer server,java.util.function.BiConsumer<Long,Runnable> schedule,Consumer<String> finish)throws Exception {
        var fixture=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("r3-military-fixture.json"))).getAsJsonObject();
        server.overworld().getChunk(0,0);
        schedule.accept(120L,()->{try{
            var actor=new FakePlayer(server.overworld(),new GameProfile(UUID.fromString(fixture.get("actor").getAsString()),"MilitaryOne"));
            var unit=server.overworld().getEntity(UUID.fromString(fixture.get("unit").getAsString()));check(unit!=null,"native leased entity persisted and loaded after restart");
            var restored=new com.google.gson.Gson().toJsonTree(RecruitsMobilization.snapshot(unit));
            check(restored.equals(fixture.get("orders")),"original native orders and ownership survived restart: actual="+restored+" expected="+fixture.get("orders"));
            check(com.ultimakingdoms.warfare.mobilization.MobilizationEvents.get(server).status(actor).stream().anyMatch(s->s.startsWith(fixture.get("lease").getAsString())&&s.contains(" RESTORED ")),"pending restoration acknowledged only after native entity reload");
            check(CampaignService.get(server).control(UUID.fromString(fixture.get("settlement").getAsString())).orElseThrow().autonomy().equals("AUTONOMOUS"),"ratified sovereignty history survived restart and native breach");
            check(RecruitsMilitary.relation(server,"mil_a","mil_b")==Relation.ALLY,"restart does not rewrite divergent native diplomacy");
            finish.accept("PASS integration military restart: native restored orders, lease acknowledgment, sovereignty history and no relation writeback");
        }catch(Throwable failure){failure.printStackTrace();finish.accept("FAIL "+failure);}});
    }
    static SettlementView settlement(KingdomsService service, net.minecraft.server.level.ServerLevel level, BlockPos pos, ResourceLocation kingdom, String name) {
        return service.registerCandidate(level, SettlementCandidate.external(level.dimension(),pos,12,SettlementBounds.around(pos,12),new ResourceLocation("ultima_acceptance:military"),name,kingdom,Map.of(),name));
    }
}
