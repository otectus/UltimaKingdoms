package com.ultimakingdoms.acceptance;

import com.mojang.authlib.GameProfile;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.compat.recruits.RecruitsObservation;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.util.FakePlayerFactory;
import java.util.*;
import java.util.function.Consumer;
import static com.ultimakingdoms.acceptance.IntegrationScenario.*;

/** Only the isolated acceptance jar mutates native military fixtures. */
final class WarfareScenario {
    static void run(MinecraftServer server, String phase, Consumer<String> finish) throws Exception {
        var kingdoms = UltimaKingdomsApi.get(server);
        var level = server.overworld();
        var pos = new BlockPos(0, 64, 0);
        var profile = new GameProfile(PLAYER, "R3Operator");
        var actor = FakePlayerFactory.get(level, profile); actor.setPos(0, 64, 0);
        server.getPlayerList().op(profile);
        var observer = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("721be7a7-110e-4a66-a24b-3ad63d3c93ff"), "R3Civilian"));
        observer.setPos(0, 64, 0);
        var runtime = WarfareRuntime.get(server);
        check(WarfareConfig.ENABLED.get(), "R3 defaults enabled in isolated server without a config override");
        if (!phase.equals("r3")) {
            var settlement = kingdoms.findSettlement("R3 Harbor").orElseThrow();
            if (phase.equals("r3-future")) {
                check(!ControlSavedData.get(server).writable(), "future control payload remains read-only");
                finish.accept("PASS integration R3 future-schema preservation"); return;
            }
            runtime.reconcile(); runtime.reconcile();
            var lines = runtime.view(actor, settlement.id());
            check(lines.stream().anyMatch(s -> s.contains("OCCUPIED")), "occupation retained across restart/provider absence");
            check(lines.stream().anyMatch(s -> s.contains("Observation #3.")), "one siege retains one control transition");
            check(settlement.kingdomId().equals(A), "original civic kingdom preserved across restart");
            if (phase.equals("r3-absent"))
                check(lines.stream().anyMatch(s -> s.contains("ABSENT")), "provider absence suspends and labels retained history");
            else check(RecruitsObservation.here(actor).ownerId().equals("r3_attacker"), "native and Ultima owners agree after restart");
            finish.accept("PASS integration " + phase); return;
        }
        Object factions = Class.forName("com.talhanation.recruits.FactionEvents").getField("recruitsFactionManager").get(null);
        for (String name : List.of("r3_defender", "r3_attacker"))
            call(factions, "addTeam", name, name, UUID.randomUUID(), name + "_leader", new CompoundTag(), (byte) 1, net.minecraft.ChatFormatting.BLUE);
        Object defender = call(factions, "getFactionByStringID", "r3_defender");
        Object attacker = call(factions, "getFactionByStringID", "r3_attacker");
        Object manager = Class.forName("com.talhanation.recruits.ClaimEvents").getField("recruitsClaimManager").get(null);
        Class<?> factionType = defender.getClass(), claimType = Class.forName("com.talhanation.recruits.world.RecruitsClaim");
        Object claim = claimType.getConstructor(String.class, factionType).newInstance("R3 Harbor Claim", defender);
        call(claim, "setCenter", new ChunkPos(0, 0)); call(claim, "addChunk", new ChunkPos(0, 0));
        call(manager, "addOrUpdateClaim", level, claim);
        Object second = claimType.getConstructor(String.class, factionType).newInstance("R3 Attacker Claim", attacker);
        call(second, "setCenter", new ChunkPos(8, 0)); call(second, "addChunk", new ChunkPos(8, 0));
        call(manager, "addOrUpdateClaim", level, second);
        var settlement = kingdoms.registerCandidate(level, SettlementCandidate.external(level.dimension(), pos, 12,
                SettlementBounds.around(pos, 12), id("ultima_acceptance:r3"), "harbor", A, Map.of(), "R3 Harbor"));
        var original = settlement;
        var resident = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
        resident.setPos(0, 64, 0); kingdoms.setResidence(resident, settlement.id());
        var identity = kingdoms.getCivicIdentity(resident);
        runtime.mapHere(actor, A);
        actor.setPos(128, 64, 0); runtime.mapHere(actor, B); actor.setPos(0, 64, 0);
        call(manager, "save", level); call(factions, "save", level); server.saveEverything(false, true, true);
        runtime.bindHere(actor, settlement.id());
        long boundRevision = runtime.revision();
        runtime.bindHere(actor, settlement.id()); check(boundRevision == runtime.revision(), "duplicate binding is idempotent");
        boolean denied = false;
        try { runtime.mapHere(observer, A); } catch (IllegalArgumentException expected) { denied = true; }
        check(denied, "civilian cannot create native mapping");
        check(runtime.view(observer, settlement.id()).equals(List.of("Control information unavailable.")), "guessed settlement UUID hides control history");
        SettlementKnowledge.get(server).discover(observer.getUUID(), settlement.id());
        check(runtime.view(observer, settlement.id()).stream().anyMatch(s -> s.contains("CONTROLLED")), "local discovered civilian can inspect control");
        observer.setPos(500, 64, 500);
        check(runtime.view(observer, settlement.id()).equals(List.of("Visit this settlement to inspect its control history.")), "discovery alone grants no remote live intelligence");
        observer.setPos(0, 64, 0);
        var forced = Set.copyOf(level.getForcedChunks());
        // This legacy observation fixture bypasses campaign policy; military policy has its own all-enabled scenario.
        WarfareConfig.MILITARY.set(false);
        call(claim, "setUnderSiege", true, level);
        runtime.reconcile(); runtime.reconcile();
        check(runtime.revision() == boundRevision, "unsaved native siege creates no durable control event");
        call(manager, "save", level); server.saveEverything(false, true, true);
        runtime.reconcile(); runtime.reconcile();
        check(runtime.view(actor, settlement.id()).stream().anyMatch(s -> s.contains("UNDER_SIEGE")), "saved native siege appears in history");
        call(claim, "addParty", claimType.getField("attackingParties").get(claim), attacker);
        call(claim, "setSiegeSuccess", level);
        long beforeSave = runtime.revision(); runtime.reconcile(); runtime.reconcile();
        check(runtime.revision() == beforeSave, "native success event awaits provider disk confirmation");
        call(manager, "save", level); server.saveEverything(false, true, true);
        runtime.reconcile(); runtime.reconcile();
        check(runtime.view(actor, settlement.id()).stream().anyMatch(s -> s.contains("OCCUPIED")), "saved native siege records provisional occupation");
        WarfareConfig.MILITARY.set(true);
        long occupiedRevision = runtime.revision(); runtime.reconcile(); runtime.reconcile();
        check(occupiedRevision == runtime.revision(), "duplicate reconciliation does not repeat control transition");
        var after = kingdoms.getSettlement(settlement.id()).orElseThrow();
        check(after.kingdomId().equals(original.kingdomId()) && after.displayName().equals(original.displayName())
                && after.externalRefs().equals(original.externalRefs()) && kingdoms.getCivicIdentity(resident).equals(identity),
                "occupation preserves civic name, kingdom, external references and NPC identity");
        check(Set.copyOf(level.getForcedChunks()).equals(forced), "observation introduces no forced chunks");
        denied = false;
        try { kingdoms.setKingdom(settlement.id(), B); } catch (IllegalArgumentException | IllegalStateException expected) { denied = true; }
        check(denied, "bound settlement cannot silently change civic sovereignty");
        WarfareConfig.ENABLED.set(false); runtime.reconcile();
        check(runtime.revision() == occupiedRevision, "disabling retains control records");
        WarfareConfig.ENABLED.set(true);
        // Exercise explicit administrative recovery independently of the occupied harbor retained for restart.
        var outpostPos = new BlockPos(128, 64, 0);
        var outpost = kingdoms.registerCandidate(level, SettlementCandidate.external(level.dimension(), outpostPos, 12,
                SettlementBounds.around(outpostPos, 12), id("ultima_acceptance:r3"), "outpost", B, Map.of(), "R3 Outpost"));
        actor.setPos(128, 64, 0); runtime.bindHere(actor, outpost.id());
        long retirementRevision = runtime.revision();
        denied = false;
        try { runtime.retire(actor, outpost.id(), retirementRevision - 1); } catch (IllegalArgumentException expected) { denied = true; }
        check(denied, "stale administrative retirement refused");
        denied = false;
        try { runtime.retire(observer, outpost.id(), retirementRevision); } catch (IllegalArgumentException expected) { denied = true; }
        check(denied, "civilian cannot retire a binding");
        WarfareConfig.ENABLED.set(false);
        runtime.retire(actor, outpost.id(), retirementRevision);
        check(runtime.view(actor, outpost.id()).stream().anyMatch(s -> s.contains("RETIRED")), "disabled-feature retirement preserves history");
        check(RecruitsObservation.here(actor).claimId().equals(call(second, "getUUID").toString()), "retirement leaves native claim unchanged");
        kingdoms.setKingdom(outpost.id(), A);
        check(kingdoms.getSettlement(outpost.id()).orElseThrow().kingdomId().equals(A), "explicit retirement releases civic migration preflight");
        WarfareConfig.ENABLED.set(true);
        denied = false;
        try { runtime.bindHere(actor, outpost.id()); } catch (IllegalArgumentException expected) { denied = true; }
        check(denied, "retired native claim identity cannot be recycled");
        actor.setPos(0, 64, 0);
        server.saveEverything(false, true, true);
        finish.accept("PASS integration R3 native siege save fence, privacy, identity and replay");
    }
}
