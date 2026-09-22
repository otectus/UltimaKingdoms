package com.ultimakingdoms.warfare.contracts;

import com.google.gson.Gson;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CivilianContractDataTest {
    @Test void acceptedAndCompletedTermsRoundTripWithoutMutation() {
        var offered = contract(CivilianContractData.Status.OFFERED, null, null);
        UUID instance = UUID.randomUUID();
        var accepted = offered.accepted(instance);
        var completed = accepted.completed(UUID.randomUUID(), instance, 1_500);
        var tag = encoded(List.of(completed));
        var loaded = CivilianContractData.load(tag);
        assertTrue(loaded.writable());
        assertEquals(tag, loaded.save(new CompoundTag()));
        assertEquals(completed.completion(), loaded.completed(completed.player(), completed.settlement(),
                CivilianContractKind.RELIEF).orElseThrow().completion());
    }

    @Test void activeProofBlocksFarmingButCancelledWorkDoesNot() {
        UUID player = UUID.randomUUID(), settlement = UUID.randomUUID(), instance = UUID.randomUUID();
        var completed = reidentify(contract(CivilianContractData.Status.OFFERED, null, null), player, settlement)
                .accepted(instance).completed(UUID.randomUUID(), instance, 2_000);
        var cancelled = reidentify(contract(CivilianContractData.Status.OFFERED, null, null), player, settlement)
                .accepted(UUID.randomUUID()).cancelled();
        var loaded = CivilianContractData.load(encoded(List.of(cancelled, completed)));
        assertEquals(completed.id(), loaded.active(player, settlement, CivilianContractKind.RELIEF, 1_500).orElseThrow().id());
        assertEquals(completed.id(), loaded.completed(player, settlement, CivilianContractKind.RELIEF).orElseThrow().id());
    }

    @Test void futureDuplicateAndBrokenLifecycleStayPreservedReadOnly() {
        var contract = contract(CivilianContractData.Status.OFFERED, null, null);
        var duplicate = encoded(List.of(contract, contract));
        var future = encoded(List.of(contract)); future.putInt("Schema", 99); future.putString("Future", "keep");
        var broken = encoded(List.of(contract)); broken.putString("Contracts", "[{\"id\":null}]");
        for (var tag : List.of(duplicate, future, broken)) {
            var loaded = CivilianContractData.load(tag);
            assertFalse(loaded.writable()); assertEquals(tag, loaded.save(new CompoundTag()));
        }
    }

    @Test void bindingsAreNamespacedAndCanonical() {
        UUID id = UUID.randomUUID();
        assertTrue(CivilianContractService.handles("r3:" + id));
        assertFalse(CivilianContractService.handles(id.toString()));
        assertFalse(CivilianContractService.handles("r3:" + id.toString().toUpperCase()));
        assertFalse(CivilianContractService.handles("r3:nope"));
    }

    @Test void freshOfferReplacesExpiredUnacceptedOfferAndRemainsWritableAfterRestart() {
        UUID player = UUID.randomUUID(), settlement = UUID.randomUUID();
        var expired = reidentify(contract(CivilianContractData.Status.OFFERED, null, null), player, settlement);
        var fresh = new CivilianContractData.Contract(UUID.randomUUID(), player, expired.giver(), expired.institution(),
                settlement, expired.organization(), expired.kind(), expired.quest(), expired.recognizedKingdom(),
                expired.nativeController(), expired.autonomy(), expired.controlSequence(), expired.politicalRevision(),
                expired.institutionRevision(), 2_300, 3_500, CivilianContractData.Status.OFFERED, null, null);

        var prepared = CivilianContractData.preparePut(List.of(expired), fresh, 2_300).orElseThrow();
        assertFalse(prepared.containsKey(expired.id()));
        var restarted = CivilianContractData.load(encoded(List.copyOf(prepared.values())));
        assertTrue(restarted.writable());
        assertEquals(fresh.id(), restarted.active(player, settlement, CivilianContractKind.RELIEF, 2_300)
                .orElseThrow().id());

        var accepted = expired.accepted(UUID.randomUUID());
        assertTrue(CivilianContractData.preparePut(List.of(accepted), fresh, 2_300).isEmpty(),
                "an expired acceptance deadline must never retire already accepted work");
    }

    private static CompoundTag encoded(List<CivilianContractData.Contract> values) {
        var tag = new CompoundTag(); tag.putInt("Schema", 1);
        tag.putString("Contracts", new Gson().toJson(values)); return tag;
    }

    private static CivilianContractData.Contract contract(CivilianContractData.Status status, UUID instance,
                                                           CivilianContractData.Completion completion) {
        return new CivilianContractData.Contract(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "ultima_kingdoms:lamplighters", "relief",
                "ultima:warfare/relief", "ultima:madera", "recruits:blue", "protected", 4, 8, 3,
                1_000, 2_200, status, instance, completion);
    }

    private static CivilianContractData.Contract reidentify(CivilianContractData.Contract c, UUID player, UUID settlement) {
        return copy(c, UUID.randomUUID(), player, settlement);
    }

    private static CivilianContractData.Contract copy(CivilianContractData.Contract c, UUID id, UUID player, UUID settlement) {
        return new CivilianContractData.Contract(id, player, c.giver(), c.institution(), settlement, c.organization(),
                c.kind(), c.quest(), c.recognizedKingdom(), c.nativeController(), c.autonomy(), c.controlSequence(),
                c.politicalRevision(), c.institutionRevision(), c.offeredAt(), c.offerExpires(), c.status(),
                c.instance(), c.completion());
    }
}
