package com.ultimakingdoms.api.gating;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingdomPredicateTest {
    private static final ResourceLocation LUNARI = new ResourceLocation("ultima_kingdoms", "lunari");
    private static final ResourceLocation YEW = new ResourceLocation("ultima_kingdoms", "yew");

    @Test
    void exclusionWinsAndEmptyIncludeMatchesKnownKingdoms() {
        KingdomPredicate excluded = new KingdomPredicate(KingdomSubject.GIVER_RESIDENCE,
                Set.of(LUNARI), Set.of(LUNARI), UnknownKingdomPolicy.DENY);
        assertFalse(excluded.matches(LUNARI));

        KingdomPredicate anyKnown = new KingdomPredicate(KingdomSubject.GIVER_RESIDENCE,
                Set.of(), Set.of(LUNARI), UnknownKingdomPolicy.DENY);
        assertTrue(anyKnown.matches(YEW));
        assertFalse(anyKnown.matches(LUNARI));
    }

    @Test
    void unknownDefaultsDenyAndCanBeExplicitlyAllowed() {
        KingdomPredicate defaults = KingdomPredicate.fromJson(JsonParser.parseString("{}"));
        assertEquals(KingdomSubject.GIVER_RESIDENCE, defaults.subject());
        assertEquals(UnknownKingdomPolicy.DENY, defaults.whenUnknown());
        assertFalse(defaults.matches((ResourceLocation) null));

        KingdomPredicate allowed = KingdomPredicate.fromJson(
                JsonParser.parseString("{\"when_unknown\":\"allow\"}"));
        assertTrue(allowed.matches((ResourceLocation) null));
    }

    @Test
    void strictParserRejectsUnknownFieldsDuplicatesAndNonCanonicalIds() {
        assertThrows(IllegalArgumentException.class, () -> KingdomPredicate.fromJson(
                JsonParser.parseString("{\"allow_unknown\":true}")));
        assertThrows(IllegalArgumentException.class, () -> KingdomPredicate.fromJson(
                JsonParser.parseString("{\"include\":[\"ultima_kingdoms:lunari\",\"ultima_kingdoms:lunari\"]}")));
        assertThrows(IllegalArgumentException.class, () -> KingdomPredicate.fromJson(
                JsonParser.parseString("{\"include\":[\"Lunari\"]}")));
    }

    @Test
    void codecRoundTripsTheImmutablePredicate() {
        KingdomPredicate expected = new KingdomPredicate(KingdomSubject.GIVER_ORIGIN,
                Set.of(LUNARI, YEW), Set.of(), UnknownKingdomPolicy.ALLOW);
        var encoded = KingdomPredicate.CODEC.encodeStart(JsonOps.INSTANCE, expected)
                .getOrThrow(false, message -> { });
        KingdomPredicate decoded = KingdomPredicate.CODEC.parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(false, message -> { });
        assertEquals(expected, decoded);
        assertThrows(UnsupportedOperationException.class, () -> decoded.include().add(LUNARI));
    }
}
