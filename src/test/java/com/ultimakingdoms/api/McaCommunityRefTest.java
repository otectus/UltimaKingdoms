package com.ultimakingdoms.api;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McaCommunityRefTest {
    @Test
    void persistedFormRoundTrips() {
        McaCommunityRef reference = new McaCommunityRef(new ResourceLocation("minecraft:the_nether"), 42);
        assertEquals("minecraft:the_nether#42", reference.format());
        assertEquals(Optional.of(reference), McaCommunityRef.parse(reference.format()));
    }

    @Test
    void malformedValuesFailSafely() {
        assertTrue(McaCommunityRef.parse(null).isEmpty());
        assertTrue(McaCommunityRef.parse("minecraft:overworld").isEmpty());
        assertTrue(McaCommunityRef.parse("minecraft:overworld#x").isEmpty());
        assertTrue(McaCommunityRef.parse("minecraft:overworld#-1").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new McaCommunityRef(new ResourceLocation("minecraft:overworld"), -1));
    }
}
