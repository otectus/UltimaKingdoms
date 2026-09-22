package dev.otectus.mcaquests.state;

import dev.otectus.mcaquests.api.QuestCompletionReceipt;
import dev.otectus.mcaquests.support.TestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionReceiptOutboxTest {
    private static final ResourceLocation CONSUMER = new ResourceLocation("test", "guilds");
    private static final ResourceLocation AUDIT = new ResourceLocation("test", "audit");
    private static final UUID PLAYER = UUID.fromString("46b1509b-23ab-48d1-893b-e9f30fd72bc2");

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensureBootstrapped();
    }

    @Test
    void institutionalCompletionRequiresItsExactActiveConsumerLease() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        ResourceLocation civic = new ResourceLocation("ultima_kingdoms", "regional_civic_network");
        assertFalse(outbox.hasActiveConsumer(civic, 10L));
        outbox.read(AUDIT, 8, 10L);
        assertFalse(outbox.hasActiveConsumer(civic, 10L), "an unrelated subscriber is insufficient");
        outbox.read(civic, 8, 10L);
        assertTrue(outbox.hasActiveConsumer(civic, 10L));
        assertFalse(outbox.hasActiveConsumer(civic,
                10L + CompletionReceiptOutbox.SUBSCRIPTION_LEASE_TICKS + 1L));
    }

    @Test
    void receiptIsInvisibleUntilDurabilityFenceAndRoundTripsFrozenContext() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        outbox.read(CONSUMER, 8, 9L);
        ActiveQuest quest = quest(10L);
        QuestCompletionReceipt captured = outbox.append(PLAYER, quest, 90L);

        assertEquals("pending_save", outbox.status());
        assertTrue(outbox.read(CONSUMER, 8, 90L).isEmpty(), "an in-memory completion is not delivery evidence");

        // A successful disk verification is the only in-process transition that publishes the receipt.
        outbox.markDurable(captured.receiptId());
        QuestCompletionReceipt visible = outbox.read(CONSUMER, 8, 90L).get(0);
        assertEquals(captured, visible);
        assertEquals(12, visible.acceptedVillageId().orElseThrow());
        assertEquals(new ResourceLocation("ultima_kingdoms", "madera"),
                visible.kingdomBinding().orElseThrow().kingdomId());
        assertEquals("guild_hall", visible.civicBuildingBinding().orElseThrow().family());

        // A receipt loaded from a player file has necessarily crossed that file's fence.
        CompletionReceiptOutbox restored = new CompletionReceiptOutbox();
        restored.load(outbox.save());
        assertEquals(List.of(captured), restored.read(CONSUMER, 8, 91L));
        assertEquals("ready", restored.status());
    }

    @Test
    void failedAcknowledgementRollsBackAndReplaysAfterRestart() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        outbox.read(CONSUMER, 8, 9L);
        QuestCompletionReceipt receipt = outbox.append(PLAYER, quest(10L), 90L);
        outbox.markDurable(receipt.receiptId());
        assertFalse(outbox.wasAcknowledged(CONSUMER, receipt.receiptId()));
        assertTrue(outbox.acknowledge(CONSUMER, receipt.providerEpoch(), receipt.receiptId()));

        // Models the API's response to a failed acknowledgement save/reread.
        outbox.rollbackAcknowledgement(CONSUMER, receipt.receiptId(), false);
        assertEquals(List.of(receipt), outbox.read(CONSUMER, 8, 90L));

        CompletionReceiptOutbox restored = new CompletionReceiptOutbox();
        restored.load(outbox.save());
        assertEquals(List.of(receipt), restored.read(CONSUMER, 8, 91L),
                "a failed ack must not strand the only durable evidence");
    }

    @Test
    void durableAcknowledgementSurvivesRestartAndOtherConsumerStillSeesReceipt() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        outbox.read(CONSUMER, 8, 9L);
        outbox.read(AUDIT, 8, 9L);
        QuestCompletionReceipt receipt = outbox.append(PLAYER, quest(10L), 90L);
        outbox.markDurable(receipt.receiptId());
        assertTrue(outbox.acknowledge(CONSUMER, receipt.providerEpoch(), receipt.receiptId()));

        CompletionReceiptOutbox restored = new CompletionReceiptOutbox();
        restored.load(outbox.save());
        assertTrue(restored.read(CONSUMER, 8, 91L).isEmpty());
        assertEquals(List.of(receipt), restored.read(AUDIT, 8, 91L));
    }

    @Test
    void expiredOrUnacknowledgedConsumerNeverLosesItsFrozenEvidence() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        outbox.read(CONSUMER, 8, 9L);
        QuestCompletionReceipt receipt = outbox.append(PLAYER, quest(10L), 90L);
        outbox.markDurable(receipt.receiptId());
        long longAfterRetention = 90L + CompletionReceiptOutbox.ACKNOWLEDGED_RETENTION_TICKS + 1L;

        assertTrue(outbox.canAppend(longAfterRetention));
        assertEquals(1, outbox.size(), "lease expiry cannot discard unacknowledged evidence");
        outbox.read(CONSUMER, 8, longAfterRetention);
        assertTrue(outbox.canAppend(longAfterRetention));
        assertEquals(1, outbox.size(), "a registered but absent consumer still owns its unacked evidence");

        assertTrue(outbox.acknowledge(CONSUMER, receipt.providerEpoch(), receipt.receiptId()));
        assertTrue(outbox.canAppend(longAfterRetention));
        assertEquals(0, outbox.size(), "fully acknowledged old evidence may be reclaimed");
    }

    @Test
    void futureAndCorruptSchemasArePreservedAndFailClosed() {
        CompoundTag future = new CompoundTag();
        future.putInt("schema", CompletionReceiptOutbox.SCHEMA + 1);
        future.putString("future_payload", "keep me");
        CompletionReceiptOutbox futureOutbox = new CompletionReceiptOutbox();
        futureOutbox.load(future);
        assertEquals("future_schema:" + (CompletionReceiptOutbox.SCHEMA + 1), futureOutbox.status());
        assertTrue(futureOutbox.shouldCapture(100L),
                "unknown future state must route completion through the failing preflight");
        assertFalse(futureOutbox.canAppend(100L));
        assertEquals(future, futureOutbox.save());

        CompoundTag corrupt = new CompoundTag();
        corrupt.putInt("schema", CompletionReceiptOutbox.SCHEMA);
        corrupt.putLong("revision", 1L); // revision without the required epoch
        CompletionReceiptOutbox corruptOutbox = new CompletionReceiptOutbox();
        corruptOutbox.load(corrupt);
        assertEquals("corrupt", corruptOutbox.status());
        assertFalse(corruptOutbox.shouldCapture(100L), "corrupt empty state owes no consumer evidence");
        assertFalse(corruptOutbox.canAppend(100L));
        assertEquals(corrupt, corruptOutbox.save());

        CompoundTag corruptWithConsumer = corrupt.copy();
        corruptWithConsumer.putString("consumers", "malformed but possibly subscribed");
        CompletionReceiptOutbox guarded = new CompletionReceiptOutbox();
        guarded.load(corruptWithConsumer);
        assertTrue(guarded.shouldCapture(100L),
                "unreadable consumer state must fail closed instead of allowing unreceipted completion");
        assertFalse(guarded.canAppend(100L));
    }

    @Test
    void unacknowledgedCapacityAppliesBackpressureWithoutEviction() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        outbox.read(CONSUMER, 8, 99L);
        for (int i = 0; i < CompletionReceiptOutbox.MAX_RECEIPTS; i++) {
            outbox.read(CONSUMER, 8, i + 99L);
            QuestCompletionReceipt receipt = outbox.append(PLAYER, quest(i + 1L), i + 100L);
            outbox.markDurable(receipt.receiptId());
        }

        assertEquals("full", outbox.status());
        long afterLease = CompletionReceiptOutbox.ACKNOWLEDGED_RETENTION_TICKS * 2L;
        assertFalse(outbox.shouldCapture(afterLease), "stopped consumer no longer opts future quests in");
        assertFalse(outbox.canAppend(afterLease));
        assertEquals(CompletionReceiptOutbox.MAX_RECEIPTS, outbox.size(),
                "an active intended consumer applies backpressure instead of losing evidence");
    }

    @Test
    void moreThanCapacityFullyAcknowledgedCompletionsKeepFlowingAndAckReplaySurvivesRestart() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        QuestCompletionReceipt first = null;
        for (int i = 0; i < CompletionReceiptOutbox.MAX_RECEIPTS + 32; i++) {
            long now = i + 100L;
            outbox.read(CONSUMER, 8, now);
            QuestCompletionReceipt receipt = outbox.append(PLAYER, quest(i + 1L), now);
            if (first == null) first = receipt;
            outbox.markDurable(receipt.receiptId());
            assertTrue(outbox.acknowledge(CONSUMER, receipt.providerEpoch(), receipt.receiptId()));
        }

        assertEquals(CompletionReceiptOutbox.MAX_RECEIPTS, outbox.size());
        assertTrue(outbox.acknowledge(CONSUMER, first.providerEpoch(), first.receiptId()),
                "a pressure-retired receipt keeps an idempotent ack tombstone");
        assertFalse(outbox.acknowledge(AUDIT, first.providerEpoch(), first.receiptId()),
                "a consumer outside the frozen cohort cannot use the tombstone");
        assertFalse(outbox.acknowledge(CONSUMER, UUID.randomUUID(), first.receiptId()));

        CompletionReceiptOutbox restored = new CompletionReceiptOutbox();
        restored.load(outbox.save());
        assertTrue(restored.acknowledge(CONSUMER, first.providerEpoch(), first.receiptId()),
                "ack -> retire -> restart remains safely replayable");
        assertFalse(restored.acknowledge(AUDIT, first.providerEpoch(), first.receiptId()));
    }

    @Test
    void fullMixedQueueRetiresAcknowledgedEntryButNeverUnacknowledgedEvidence() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        outbox.read(CONSUMER, 8, 99L);
        QuestCompletionReceipt unacknowledged = null;
        QuestCompletionReceipt acknowledged = null;
        for (int i = 0; i < CompletionReceiptOutbox.MAX_RECEIPTS; i++) {
            long now = i + 100L;
            outbox.read(CONSUMER, 8, now);
            QuestCompletionReceipt receipt = outbox.append(PLAYER, quest(i + 1L), now);
            outbox.markDurable(receipt.receiptId());
            if (i == 0) unacknowledged = receipt;
            else {
                assertTrue(outbox.acknowledge(CONSUMER, receipt.providerEpoch(), receipt.receiptId()));
                if (acknowledged == null) acknowledged = receipt;
            }
        }

        assertTrue(outbox.canAppend(356L), "a fully acknowledged row makes pressure room");
        assertEquals(CompletionReceiptOutbox.MAX_RECEIPTS - 1, outbox.size());
        assertEquals(List.of(unacknowledged), outbox.read(CONSUMER, 1, 356L),
                "the oldest unacknowledged evidence remains readable");
        assertTrue(outbox.acknowledge(CONSUMER, acknowledged.providerEpoch(), acknowledged.receiptId()),
                "the retired acknowledged row remains an idempotent replay");
    }

    @Test
    void standaloneCompletionsNeverFillOutboxOrBlockNormalQuestProgress() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        for (int i = 0; i < CompletionReceiptOutbox.MAX_RECEIPTS * 2; i++) {
            long now = i + 100L;
            assertFalse(outbox.shouldCapture(now));
            assertTrue(outbox.canAppend(now), "unsubscribed receipt state cannot block completion " + i);
            // QuestManager skips append and continues its existing reward/history pipeline in this case.
        }
        assertEquals(0, outbox.size());
        assertEquals("ready", outbox.status());
    }

    @Test
    void laterConsumerCannotClaimOrBecomeRequiredForEarlierReceipt() {
        CompletionReceiptOutbox outbox = new CompletionReceiptOutbox();
        outbox.read(CONSUMER, 8, 9L);
        QuestCompletionReceipt receipt = outbox.append(PLAYER, quest(10L), 90L);
        outbox.markDurable(receipt.receiptId());

        assertTrue(outbox.read(AUDIT, 8, 91L).isEmpty(), "new add-on gets no pre-subscription credit");
        assertFalse(outbox.acknowledge(AUDIT, receipt.providerEpoch(), receipt.receiptId()));
        assertTrue(outbox.acknowledge(CONSUMER, receipt.providerEpoch(), receipt.receiptId()));
        long expired = 90L + CompletionReceiptOutbox.ACKNOWLEDGED_RETENTION_TICKS + 1L;
        assertTrue(outbox.canAppend(expired));
        assertEquals(0, outbox.size(), "later consumers are not retroactive acknowledgement obligations");
    }

    @Test
    void playerCapabilityRoundTripRestoresReceiptForReplay() {
        PlayerQuestData data = new PlayerQuestData();
        data.readCompletionReceipts(CONSUMER, 8, 9L);
        QuestCompletionReceipt receipt = data.captureCompletionReceipt(PLAYER, quest(10L), 90L);
        data.markCompletionReceiptDurable(receipt.receiptId());

        PlayerQuestData restored = new PlayerQuestData();
        restored.load(data.save());
        assertEquals(List.of(receipt), restored.readCompletionReceipts(CONSUMER, 8, 91L));
    }

    private static ActiveQuest quest(long acceptedAt) {
        ActiveQuest quest = new ActiveQuest(new ResourceLocation("mcaquests", "guild_commission"),
                UUID.fromString("c7d1c8f0-1241-4cd8-b859-f6b6ba7862df"), Component.literal("Giver"),
                new ResourceLocation("minecraft", "librarian"),
                new ResourceLocation("minecraft", "overworld"), acceptedAt,
                OptionalLong.of(acceptedAt), OptionalInt.of(12), List.of(), null, null);
        quest.bindKingdom(new KingdomBindingSnapshot(
                UUID.fromString("41cfe97d-baf2-452e-91fe-ad8654e512fe"),
                new ResourceLocation("ultima_kingdoms", "madera"), 7L,
                new ResourceLocation("minecraft", "overworld"),
                Optional.of(new ResourceLocation("minecraft", "overworld")), OptionalInt.of(12)));
        quest.bindCivicBuilding(new CivicBuildingBinding(
                UUID.fromString("3d1c4a87-0d42-459d-819d-68a3c5b963db"),
                UUID.fromString("41cfe97d-baf2-452e-91fe-ad8654e512fe"),
                new ResourceLocation("minecraft", "overworld"), 12, 4, "guild_hall", "masonry"));
        return quest;
    }
}
