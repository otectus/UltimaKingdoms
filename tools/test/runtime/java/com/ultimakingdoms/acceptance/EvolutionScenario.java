package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.compat.recruits.*;
import com.ultimakingdoms.evolution.*;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.*;
import net.minecraftforge.common.util.FakePlayer;
import java.util.*;
import java.util.function.Consumer;
import static com.ultimakingdoms.acceptance.IntegrationScenario.*;

/** Exact packaged provider checks for R4 consent, replay, recovery, and native saved ownership. */
final class EvolutionScenario {
    private static final String A = "ultima_kingdoms:serenum", B = "ultima_kingdoms:lunari";
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void run(MinecraftServer server, Consumer<String> finish) throws Exception {
        var level = server.overworld(); var kingdoms = UltimaKingdomsApi.get(server); var politics = UltimaPoliticsApi.get(server);
        var one = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "R4One")) { @Override public boolean hasPermissions(int n) { return n <= 2; } };
        var two = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "R4Two"));
        var outsider = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "R4Neutral"));
        one.setPos(1, 64, 1); two.setPos(2, 64, 1); outsider.setPos(3, 64, 1);
        Map<UUID, ServerPlayer> online = null;
        List<ServerPlayer> livePlayers = null;
        for (var field : PlayerList.class.getDeclaredFields()) if (List.class.isAssignableFrom(field.getType()) && field.getGenericType().getTypeName().contains("ServerPlayer")) {
            field.setAccessible(true); var value = (List<ServerPlayer>)field.get(server.getPlayerList());
            if (!value.getClass().getName().contains("Unmodifiable")) { livePlayers = value; break; }
        }
        for (var field : PlayerList.class.getDeclaredFields()) if (field.getGenericType().getTypeName().contains("java.util.UUID") && field.getGenericType().getTypeName().contains("ServerPlayer")) {
            field.setAccessible(true); online = (Map<UUID, ServerPlayer>) field.get(server.getPlayerList()); break;
        }
        check(online != null, "R4 fixture locates online registry");
        check(livePlayers != null, "R4 fixture locates active player list");
        for (var p : List.of(one, two)) { online.put(p.getUUID(), p); server.getProfileCache().add(p.getGameProfile()); }
        try {
            var seatA = MilitaryScenario.settlement(kingdoms, level, new BlockPos(0,64,0), new ResourceLocation(A), "R4 Assembly");
            var seatB = MilitaryScenario.settlement(kingdoms, level, new BlockPos(160,64,0), new ResourceLocation(B), "R4 Neighbor");
            for (var seat : List.of(seatA, seatB)) {
                var leader = seat == seatA ? one : two; String kingdom = seat.kingdomId().toString();
                success(politics.execute(one, new Politics.Request(UUID.randomUUID(), politics.revision(), Politics.Action.BOOTSTRAP,
                        kingdom, kingdom + "_charter", seat.id().toString(), new Politics.Person(leader.getUUID(), Politics.Kind.PLAYER), "", "", "", 0,64,0)));
            }
            for (var p : List.of(one, two)) { SettlementKnowledge.get(server).discover(p.getUUID(), seatA.id()); SettlementKnowledge.get(server).discover(p.getUUID(), seatB.id()); }
            var evolution = EvolutionRuntime.get(server); evolution.configure(one, true, true); evolution.region(one, seatA.id(), true);
            var protection = new ProtectionService(server);
            UUID pact = parse(protection.propose(one, A, B, seatA.id(), Set.of(ProtectionState.Duty.CIVIC_AID), 168000, 1200, "Voluntary aid with peaceful exit"), "proposal ");
            protection.sign(one, pact, A, 1); protection.sign(two, pact, B, 2);
            boolean refused = false; try { protection.request(one, pact, ProtectionState.Duty.DEFENSE_ASSISTANCE, 3); } catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "out-of-scope protectorate levy denied");
            UUID obligation = parse(protection.request(one, pact, ProtectionState.Duty.CIVIC_AID, 3), "Obligation ");
            protection.refuse(two, obligation, 1, "Local workshop is serving its residents first");
            check(protection.inspect(one, pact).stream().anyMatch(s -> s.contains("REFUSED")), "subordinate retains explicit refusal");
            protection.exit(two, pact, B, 3);
            check(protection.page(one, 0).stream().anyMatch(s -> s.contains("EXIT_NOTICE")), "peaceful exit is recorded without troop confiscation");

            UUID petition = UUID.randomUUID();
            success(politics.execute(outsider, new Politics.Request(petition, politics.revision(), Politics.Action.PETITION, A,
                    "ultima_kingdoms:introduction_petition", seatA.id().toString(), null, B, "Private family concern", "", 0,0,0)));
            check(politics.history(two, 0, 50).stream().noneMatch(f -> f.sourceReceiptId().equals(petition)), "private petition absent from foreign history");
            check(politics.history(outsider, 0, 50).stream().anyMatch(f -> f.sourceReceiptId().equals(petition)), "petitioner sees durable private history");
            success(politics.execute(one, new Politics.Request(UUID.randomUUID(), politics.revision(), Politics.Action.NAME_SUCCESSOR, A,
                    "", "", new Politics.Person(two.getUUID(), Politics.Kind.PLAYER), "", "", "", 0,0,0)));
            success(politics.execute(one, new Politics.Request(UUID.randomUUID(), politics.revision(), Politics.Action.ABDICATE, A,
                    "", "", null, "", "", "", 0,0,0)));
            livePlayers.add(one);
            try { evolution.tick(); } finally { livePlayers.remove(one); }
            var lines = evolution.page(two, 0); String row = lines.stream().filter(s -> s.contains("named successor")).findFirst().orElseThrow();
            UUID scenario = UUID.fromString(row.substring(0,36));
            evolution.contribute(two, scenario, 1, EvolutionState.Outcome.SUCCEED);
            evolution.resolve(two, scenario, 2, EvolutionState.Outcome.SUCCEED, "");
            check(politics.government(A).orElseThrow().offices().get("ultima_kingdoms:leader").holder().id().equals(two.getUUID()), "scenario confirms lawful native succession");
            refused = false; try { evolution.resolve(one, scenario, 2, EvolutionState.Outcome.DECLINE, ""); } catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "competing scenario outcome cannot replace committed succession");
            check(evolution.page(outsider, 0).size() == 1, "undiscovered scenario metadata is not sent");

            Object factions = Class.forName("com.talhanation.recruits.FactionEvents").getField("recruitsFactionManager").get(null);
            call(factions, "addTeam", "r4_a", "r4_a", one.getUUID(), "R4One", new CompoundTag(), (byte)1, net.minecraft.ChatFormatting.BLUE);
            call(factions, "addTeam", "r4_b", "r4_b", two.getUUID(), "R4Two", new CompoundTag(), (byte)1, net.minecraft.ChatFormatting.RED);
            for (var p : List.of(one,two)) {
                String name = p == one ? "r4_a" : "r4_b"; var team = server.getScoreboard().getPlayerTeam(name);
                if (team == null) team = server.getScoreboard().addPlayerTeam(name);
                server.getScoreboard().addPlayerToTeam(p.getScoreboardName(), team);
            }
            Object groups = Class.forName("com.talhanation.recruits.RecruitEvents").getField("recruitsGroupsManager").get(null);
            Object firstGroup = ((List<?>)call(groups, "getPlayerGroups", one)).get(0), secondGroup = ((List<?>)call(groups, "getPlayerGroups", two)).get(0);
            UUID secondGroupId = (UUID)call(secondGroup, "getUUID");
            var unit = (Mob)net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits:recruit")).create(level);
            unit.setPos(4,64,4); unit.setNoAi(true); unit.setNoGravity(true); unit.setPersistenceRequired(); level.addFreshEntity(unit);
            check((boolean)call(unit, "hire", one, firstGroup, false), "fixture hires through native provider");
            unit.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            var before = RecruitsTransfer.snapshot(unit, one, two, secondGroupId);
            var transfers = new RecruitTransferService(server);
            var veto = new java.util.concurrent.atomic.AtomicBoolean(true);
            Class eventType = Class.forName("com.talhanation.recruits.RecruitEvent$Hired");
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, false, eventType,
                    (java.util.function.Consumer<net.minecraftforge.eventbus.api.Event>) e -> { if (veto.get()) e.setCanceled(true); });
            UUID transfer = parse(transfers.propose(one, unit.getUUID(), two.getUUID(), secondGroupId, false), "proposal ");
            transfers.consent(two, transfer, 1); server.getWorldData().overworldData().setGameTime(level.getGameTime()+1201);
            String refusal = transfers.apply(one, transfer, 2);
            check(RecruitsTransfer.matches(unit, before, false), "native hire veto restores original owner/group/count/equipment: " + refusal);
            check(transfers.inspect(one, transfer).get(0).contains("CANCELLED"), "restoration has actual disk acknowledgment");
            veto.set(false);
            transfer = parse(transfers.propose(one, unit.getUUID(), two.getUUID(), secondGroupId, false), "proposal ");
            transfers.consent(two, transfer, 1); server.getWorldData().overworldData().setGameTime(level.getGameTime()+1201);
            var forced = Set.copyOf(level.getForcedChunks()); String result = transfers.apply(one, transfer, 2);
            check(transfers.inspect(one, transfer).get(0).contains("COMPLETE"), "native transfer has disk acknowledgment: " + result);
            check(RecruitsTransfer.matches(unit, before, true), "recipient owns same recruit/equipment with balanced native counts");
            check(forced.equals(Set.copyOf(level.getForcedChunks())), "R4 transfer introduces no forced chunks");
            transfers.confirm(two, transfer); check(RecruitsTransfer.matches(unit, before, true), "confirmation replay does not add a second recruit");
            refused = false; try { transfers.inspect(outsider, transfer); } catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "private transfer is hidden from unrelated player");
            // A totals-only classifier cannot distinguish a partial hire from an unrelated loss.
            // Exercise the native accounting hook while a durable intent is pending.
            var reverse = RecruitsTransfer.snapshot(unit, two, one, before.group());
            var accountingIntent = new RecruitTransferData.Transfer(UUID.randomUUID(),unit.getUUID(),two.getUUID(),one.getUUID(),before.group(),reverse.equipment(),false,
                    level.getGameTime()+1200,level.getGameTime()+72000,1,RecruitTransferData.Phase.APPLYING,reverse,"Accounting regression fixture");
            var write = RecruitTransferData.class.getDeclaredMethod("put",MinecraftServer.class,RecruitTransferData.Transfer.class);write.setAccessible(true);
            check((boolean)write.invoke(RecruitTransferData.get(server),server,accountingIntent),"pending accounting fixture committed");
            Object counts = Class.forName("com.talhanation.recruits.RecruitEvents").getField("recruitsPlayerUnitManager").get(null);
            var dismissVeto=new java.util.concurrent.atomic.AtomicBoolean(true);
            Class dismissedType=Class.forName("com.talhanation.recruits.RecruitEvent$Dismissed");
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST,false,dismissedType,
                    (java.util.function.Consumer<net.minecraftforge.eventbus.api.Event>)e->{if(dismissVeto.get()){
                        try{call(counts,"removeRecruits",two.getUUID(),1);call(counts,"addRecruits",two.getUUID(),1);}
                        catch(Exception failure){throw new RuntimeException(failure);}e.setCanceled(true);
                    }});
            check(!RecruitsTransfer.apply(unit,two,one,reverse),"native dismissal listener veto retains current owner");dismissVeto.set(false);
            check(!RecruitTransferData.get(server).accountingSafe(unit.getUUID()),"unrelated native changes remain detected even when totals return to baseline");
            refused=false;try{RecruitsTransfer.restore(unit,two,one,reverse);}catch(IllegalArgumentException expected){refused=true;}
            check(refused && two.getUUID().equals(call(unit,"getOwnerUUID")),"diverged accounting cannot silently rewrite original or recipient ownership");
            var review=RecruitsTransfer.review(unit,reverse,false);
            refused=false;try{transfers.reconcile(two,accountingIntent.id(),2,review.fingerprint(),"Verified counts");}catch(IllegalArgumentException expected){refused=true;}
            check(refused,"non-operator cannot acknowledge diverged accounting");
            transfers.reconcile(one,accountingIntent.id(),2,review.fingerprint(),"Verified native counts after test listener restored its unrelated change");
            check(transfers.inspect(one,accountingIntent.id()).get(0).contains("RECONCILED"),"operator acknowledgment retains distinct reconciled result without changing ownership");
            var fixture = new CompoundTag(); fixture.putUUID("Transfer", transfer); fixture.putUUID("Unit", unit.getUUID()); fixture.putUUID("Recipient", two.getUUID());
            fixture.putString("Equipment", before.equipment());
            fixture.putUUID("Scenario", scenario); fixture.putUUID("Petition", petition); fixture.putUUID("Petitioner", outsider.getUUID());
            fixture.putUUID("Leader",two.getUUID());
            boolean crash=System.getProperty("ultima.acceptance.integration","").equals("r4-crash");
            if(crash){
                var pending=new RecruitTransferData.Transfer(UUID.randomUUID(),unit.getUUID(),two.getUUID(),one.getUUID(),before.group(),reverse.equipment(),false,
                        level.getGameTime()+1200,level.getGameTime()+72000,1,RecruitTransferData.Phase.APPLYING,reverse,"Durable crash-checkpoint intent");
                check((boolean)write.invoke(RecruitTransferData.get(server),server,pending),"crash fixture intent committed before native write");
                check(RecruitsTransfer.apply(unit,two,one,reverse),"crash fixture native handoff applied");
                check(RecruitsTransfer.saveAndConfirm(unit,reverse,true),"crash fixture native handoff saved before owner acknowledgment");
                fixture.putUUID("Transfer",pending.id());fixture.putUUID("Recipient",one.getUUID());
            }
            NbtIo.writeCompressed(fixture, server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("r4-fixture.dat").toFile());
            server.saveEverything(false,true,true);
            if(crash){System.out.println("CRASH_READY integration R4 native handoff saved with APPLYING intent and no completion acknowledgment");return;}
            finish.accept("PASS integration R4 political history, lawful succession, competing outcomes, protectorate consent/refusal/exit, native transfer veto recovery and durable ownership");
        } finally { online.remove(one.getUUID()); online.remove(two.getUUID()); }
    }
    static void restart(MinecraftServer server, String phase, Consumer<String> finish) throws Exception {
        var fixture = NbtIo.readCompressed(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("r4-fixture.dat").toFile());
        var actor = new FakePlayer(server.overworld(), new GameProfile(fixture.getUUID("Recipient"), "R4Two"));
        var transfers = new RecruitTransferService(server);
        if (phase.equals("r4-future")) {
            check(!EvolutionSavedData.get(server).writable(), "future evolution preserved read-only");
            check(!RecruitTransferData.get(server).writable(), "future transfer preserved read-only");
            check(RecruitTransferService.blocksMobilization(server, fixture.getUUID("Unit")), "unknown transfer state blocks conflicting mobilization");
        } else {
            check(EvolutionSavedData.get(server).writable() && RecruitTransferData.get(server).writable(), "R4 sidecars reload writable");
            if(phase.equals("r4-crash-restart")){
                check(transfers.inspect(actor,fixture.getUUID("Transfer")).get(0).contains("APPLYING"),"crash retained unacknowledged intent");
                transfers.confirm(actor,fixture.getUUID("Transfer"));
            }
            check(transfers.inspect(actor, fixture.getUUID("Transfer")).get(0).contains("COMPLETE"), "durable transfer receipt survives restart/provider absence");
            check(EvolutionRuntime.get(server).inspect(actor, fixture.getUUID("Scenario")).stream().anyMatch(s -> s.contains("RESOLVED")), "resolved shared outcome survives restart");
            var politics = UltimaPoliticsApi.get(server);
            check(politics.government(A).orElseThrow().offices().get("ultima_kingdoms:leader").holder().id().equals(fixture.getUUID("Leader")), "lawful succession survives restart");
            var stranger = new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"R4Unrelated"));
            check(politics.history(stranger,0,50).stream().noneMatch(f -> f.sourceReceiptId().equals(fixture.getUUID("Petition"))), "private petition remains filtered after restart");
            if (!phase.equals("r4-absent")) {
                var unit = server.overworld().getEntity(fixture.getUUID("Unit"));
                check(unit != null, "native transferred entity reloads");
                check(actor.getUUID().equals(call(unit,"getOwnerUUID")) && fixture.getString("Equipment").equals(RecruitsTransfer.equipment(unit)), "native recipient and unchanged equipment survive restart");
                check(transfers.confirm(actor, fixture.getUUID("Transfer")).contains("acknowledged"), "terminal confirmation replay is read-only");
            }
        }
        server.saveEverything(false,true,true);
        finish.accept("PASS integration " + phase + ": durable outcome, ownership, private history and schema/provider preservation");
    }
    private static UUID parse(String line, String prefix) { int start = line.indexOf(prefix) + prefix.length(); return UUID.fromString(line.substring(start, start + 36)); }
    private static void success(Politics.Result result) { check(result.success(), "political setup: " + result.message()); }
    private EvolutionScenario() { }
}
