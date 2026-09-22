package com.ultimakingdoms.factions;

import com.ultimakingdoms.api.factions.FactionChangeCause;
import com.ultimakingdoms.api.factions.FactionStandingRequest;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactionSavedDataTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID SETTLEMENT = UUID.fromString("00000000-0000-0000-0000-000000000043");
    private static final ResourceLocation KINGDOM = new ResourceLocation("test", "riverbend");
    private static final ResourceLocation SOURCE = new ResourceLocation("test", "migration");

    @Test
    void standingReceiptAndCursorRoundTripTogether() {
        FactionSavedData data = new FactionSavedData();
        FactionStandingRecord standing = data.getOrCreate(PLAYER, KINGDOM);
        assertEquals(125, standing.apply(125, data.nextRevision()));
        FactionStandingRequest request = new FactionStandingRequest(PLAYER, KINGDOM, 125, SOURCE,
                FactionChangeCause.MIGRATION, CORRELATION, 9L, Optional.of(SETTLEMENT),
                Optional.of("baseline"), true);
        data.record(SyncReceipt.from(request, standing.revision, 200L));
        UUID epoch = UUID.randomUUID();
        data.advanceCursor("test:consumer", epoch, 7L);

        FactionSavedData loaded = FactionSavedData.load(data.save(new CompoundTag()));

        assertEquals(125, loaded.standing(PLAYER, KINGDOM).orElseThrow().score);
        assertEquals("trusted", loaded.standing(PLAYER, KINGDOM).orElseThrow().tierId);
        assertEquals(CORRELATION, loaded.receipt(CORRELATION).orElseThrow().correlationId());
        assertEquals(9L, loaded.checkpoint(SOURCE));
        assertEquals(new FactionSavedData.SourceCursor(epoch, 7L), loaded.cursor("test:consumer"));
    }

    @Test
    void futureSchemaIsReadOnlyAndRoundTripsUnknownBytes() {
        CompoundTag future = new CompoundTag();
        future.putInt("Schema", FactionSavedData.SCHEMA + 1);
        future.putString("FuturePayload", "keep me");
        CompoundTag nested = new CompoundTag();
        nested.putLong("Unknown", 99L);
        future.put("Nested", nested);

        FactionSavedData loaded = FactionSavedData.load(future);
        loaded.setDirty();
        CompoundTag written = loaded.save(new CompoundTag());

        assertFalse(loaded.writable());
        assertFalse(loaded.isDirty());
        assertEquals(future, written);
        UUID epoch = UUID.randomUUID();
        assertFalse(loaded.consumeIgnored(new FactionSavedData.IgnoredSourceReceipt(
                epoch, 1L, UUID.randomUUID(), "cause_filtered", 1L), 2,
                "test:consumer", true));
        assertFalse(loaded.takeCustody(new PendingReputationChange(epoch, 1L, UUID.randomUUID(),
                "UNMAPPED", Map.of()), 1));
    }

    @Test
    void durableCursorAndAcknowledgementListenerAdvanceOnlyAfterSuccessfulReplacement(@TempDir Path directory)
            throws IOException {
        SharedConstants.tryDetectVersion();
        FactionSavedData data = new FactionSavedData();
        UUID epoch = UUID.randomUUID();
        AtomicInteger acknowledgements = new AtomicInteger();
        data.advanceCursor("test:consumer", epoch, 3L);
        data.onDurableSave(acknowledgements::incrementAndGet);
        Path blockingParent = directory.resolve("not-a-directory");
        Files.writeString(blockingParent, "block");

        data.save(blockingParent.resolve("ultima_kingdoms_factions.dat").toFile());

        assertTrue(data.isDirty());
        assertNull(data.durableCursor("test:consumer"));
        assertEquals(0, acknowledgements.get());

        data.save(directory.resolve("ultima_kingdoms_factions.dat").toFile());

        assertFalse(data.isDirty());
        assertEquals(new FactionSavedData.SourceCursor(epoch, 3L), data.durableCursor("test:consumer"));
        assertEquals(1, acknowledgements.get());
    }

    @Test
    void ignoredReceiptsAreBoundedDurableAndDoNotConsumePendingCapacity() {
        FactionSavedData data = new FactionSavedData();
        UUID epoch = UUID.randomUUID();
        for (long sequence = 1L; sequence <= 3L; sequence++) {
            assertTrue(data.consumeIgnored(new FactionSavedData.IgnoredSourceReceipt(
                            epoch, sequence, new UUID(0L, sequence), "cause_filtered", 100L + sequence),
                    2, "test:consumer", true));
        }

        assertEquals(2, data.ignoredReceiptCount());
        assertEquals(3L, data.ignoredConsumed());
        assertEquals(new FactionSavedData.SourceCursor(epoch, 3L), data.cursor("test:consumer"));
        assertTrue(data.takeCustody(new PendingReputationChange(epoch, 4L, new UUID(0L, 40L),
                "UNMAPPED", Map.of("reason", "no_settlement_mapping")), 1));

        FactionSavedData loaded = FactionSavedData.load(data.save(new CompoundTag()));
        assertEquals(2, loaded.ignoredReceiptCount());
        assertEquals(3L, loaded.ignoredConsumed());
        assertEquals(1, loaded.pending().size());
        assertEquals(new FactionSavedData.SourceCursor(epoch, 3L), loaded.cursor("test:consumer"));
    }

    @Test
    void pruningUsesSourceCheckpointButKeepsGenericQuestReceipts() {
        FactionSavedData data = new FactionSavedData();
        FactionStandingRequest outbox = new FactionStandingRequest(PLAYER, KINGDOM, 1, SOURCE,
                FactionChangeCause.LOCAL_REPUTATION, CORRELATION, 9L, Optional.empty(), Optional.empty(), true);
        UUID questCorrelation = UUID.randomUUID();
        FactionStandingRequest quest = new FactionStandingRequest(PLAYER, KINGDOM, 1,
                new ResourceLocation("mcaquests", "quest_reward"), FactionChangeCause.QUEST,
                questCorrelation, 0L, Optional.empty(), Optional.empty(), true);
        data.record(SyncReceipt.from(outbox, 1L, 10L));
        data.record(SyncReceipt.from(quest, 2L, 10L));

        data.pruneReceipts(100L, 20L);

        assertTrue(data.receipt(CORRELATION).isEmpty(), "checkpointed outbox receipt should age out");
        assertTrue(data.receipt(questCorrelation).isPresent(),
                "revision-zero generic receipt is the permanent exactly-once guard");
    }
}
