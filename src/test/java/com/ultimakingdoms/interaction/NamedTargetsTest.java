package com.ultimakingdoms.interaction;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NamedTargetsTest {
    @Test void identifiersOutsideTheActorCatalogueAreRefused() {
        var listed = new ActionRegistry.Choice(UUID.randomUUID().toString(), "Harbor Town");
        var choices = List.of(listed);
        assertEquals(listed, NamedTargets.resolve(choices, "Harbor Town"));
        assertEquals(listed, NamedTargets.resolve(choices, listed.value()));
        assertThrows(IllegalArgumentException.class, () -> NamedTargets.resolve(choices, UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class, () -> NamedTargets.resolve(List.of(), listed.value()));
    }
}
