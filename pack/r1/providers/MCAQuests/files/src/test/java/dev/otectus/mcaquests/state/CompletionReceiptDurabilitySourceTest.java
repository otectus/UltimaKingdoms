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
        assertFalse(source.contains("saveAll()"),
                "one receipt must not force a save of every online player's capability graph");

        String mixins = Files.readString(Path.of("src/main/resources/mcaquests.mixins.json"));
        assertTrue(mixins.contains("PlayerListAccessor"));
    }
}
