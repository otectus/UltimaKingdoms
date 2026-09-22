package com.ultimakingdoms.factions.organization;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizationDefinitionTest {
    private static final ResourceLocation FILE = new ResourceLocation("test", "ultima_factions/factions/example.json");
    private static final ResourceLocation ID = new ResourceLocation("test", "example");

    @Test
    void seededLamplightersDefinitionIsStrictlyValid() throws IOException {
        Path path = Path.of("src/main/resources/data/ultima_kingdoms/ultima_factions/factions/lamplighters.json");
        try (var reader = Files.newBufferedReader(path)) {
            OrganizationDefinition definition = OrganizationDefinitions.parseDefinition(
                    new ResourceLocation("ultima_kingdoms", "ultima_factions/factions/lamplighters.json"),
                    new ResourceLocation("ultima_kingdoms", "lamplighters"),
                    JsonParser.parseReader(reader).getAsJsonObject());
            assertEquals(3, definition.ranks().size());
            assertEquals(2, definition.services().size());
            assertEquals(12, definition.deeds().size());
            assertTrue(definition.services().stream().allMatch(rule -> rule.neutralAlternative()));
            assertTrue(definition.conflicts().isEmpty());
        }
    }

    @Test
    void unknownFieldsAreRejected() {
        JsonObject json = valid();
        json.addProperty("typo", true);
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationDefinitions.parseDefinition(FILE, ID, json));
    }

    @Test
    void servicePermissionMustBeGrantedByEveryQualifyingRank() {
        JsonObject json = valid();
        json.getAsJsonArray("ranks").get(0).getAsJsonObject()
                .add("permissions", JsonParser.parseString("[]"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OrganizationDefinitions.parseDefinition(FILE, ID, json));
        assertTrue(failure.getMessage().contains("missing from qualifying rank"));
    }

    @Test
    void militaryAndFractionalSchemaValuesAreRejected() {
        JsonObject military = valid();
        military.addProperty("military", true);
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationDefinitions.parseDefinition(FILE, ID, military));

        JsonObject fractional = valid();
        fractional.addProperty("schema", 1.5D);
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationDefinitions.parseDefinition(FILE, ID, fractional));
    }

    @Test
    void zeroAndNegativeDeedCreditAreRejected() {
        JsonObject zero = valid();
        zero.getAsJsonArray("deeds").get(0).getAsJsonObject().addProperty("credit", 0);
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationDefinitions.parseDefinition(FILE, ID, zero));

        JsonObject negative = valid();
        negative.getAsJsonArray("deeds").get(0).getAsJsonObject().addProperty("credit", -1);
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationDefinitions.parseDefinition(FILE, ID, negative));
    }

    private static JsonObject valid() {
        return JsonParser.parseString("""
                {
                  "schema": 1,
                  "id": "test:example",
                  "kind": "guild",
                  "military": false,
                  "name_key": "organization.test.example",
                  "description_key": "organization.test.example.description",
                  "membership": {"exclusive_group": null, "conflicts": []},
                  "ranks": [
                    {"id": "test:member", "minimum_standing": 0, "permissions": ["test:service"]}
                  ],
                  "services": [
                    {"permission": "test:service", "minimum_rank": "test:member",
                     "minimum_standing": 0, "required_deeds": 1}
                  ],
                  "deeds": [
                    {"quest_id": "test:quest", "credit": 10}
                  ]
                }
                """).getAsJsonObject();
    }
}
