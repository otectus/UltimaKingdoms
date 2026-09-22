package dev.otectus.mcaquests.state;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionReceiptDurabilitySourceTest {
    @Test
    void durabilityFenceSavesOnlyTheReceiptOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/otectus/mcaquests/state/CompletionReceiptDurability.java"));
        assertTrue(source.contains("mcaquests$savePlayer(player)"));
        assertTrue(source.contains("containsActiveSnapshot(disk, expected)"));
        assertFalse(source.contains("saveAll()"),
                "one receipt must not force a save of every online player's capability graph");

        String mixins = Files.readString(Path.of("src/main/resources/mcaquests.mixins.json"));
        assertTrue(mixins.contains("PlayerListAccessor"));
    }

    @Test
    void institutionalLeaseRenewalCannotTriggerAPlayerSave() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/otectus/mcaquests/api/McaQuestsApi.java"));
        int start = source.indexOf("public static boolean renewInstitutionalCompletionConsumer");
        int end = source.indexOf("public static boolean openCommissionMenu", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("data.readCompletionReceipts"));
        assertTrue(method.contains("\"ready\".equals(data.completionReceiptStatus())"));
        assertFalse(method.contains("flushPending"),
                "commit-time policy validation must not save half-finished native completion state");
    }
}
