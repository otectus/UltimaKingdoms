package dev.otectus.mcaquests.quest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcaquests.quest.condition.ConditionTypes;
import dev.otectus.mcaquests.quest.condition.composite.AllOfCondition;
import dev.otectus.mcaquests.quest.condition.composite.AnyOfCondition;
import dev.otectus.mcaquests.quest.condition.leaf.InstitutionalServiceAvailableCondition;
import dev.otectus.mcaquests.quest.condition.leaf.IsPlayerSpouseCondition;
import dev.otectus.mcaquests.support.TestBootstrap;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstitutionalCommissionBridgeTest {
    static { TestBootstrap.ensureBootstrapped(); }

    @Test
    void onlyCanonicalUuidBindingsAreAccepted() {
        assertTrue(InstitutionalCommissionBridge.validBinding(
                "11111111-2222-3333-4444-555555555555"));
        assertFalse(InstitutionalCommissionBridge.validBinding("11111111222233334444555555555555"));
        assertFalse(InstitutionalCommissionBridge.validBinding(
                "11111111-2222-3333-4444-555555555555-extra"));
    }

    @Test
    void validationFailsClosedForMissingOrMalformedApiResults() {
        assertTrue(InstitutionalCommissionBridge.validationResult("").isEmpty());
        assertFalse(InstitutionalCommissionBridge.validationResult("service suspended").isEmpty());
        assertFalse(InstitutionalCommissionBridge.validationResult(null).isEmpty());
        assertFalse(InstitutionalCommissionBridge.validationResult(Boolean.TRUE).isEmpty());
        assertFalse(InstitutionalCommissionBridge.validationResult("x".repeat(513)).isEmpty());
    }

    @Test
    void institutionalSurfaceAcceptsOnlyConcreteFixedEmeraldRewards() {
        assertTrue(InstitutionalCommissionBridge.supportedDefinition(parse(
                gate() + ",\"institutional_commission\":true,\"rewards\":[{\"type\":\"mcaquests:item\","
                        + "\"item\":\"minecraft:emerald\",\"count\":6}]")));
        assertFalse(InstitutionalCommissionBridge.supportedDefinition(parse(
                gate() + ",\"rewards\":[{\"type\":\"mcaquests:item\",\"item\":\"minecraft:emerald\","
                        + "\"count\":6}]")), "ordinary definitions stay on the ordinary surface");
        assertFalse(InstitutionalCommissionBridge.supportedDefinition(parse(
                gate() + ",\"institutional_commission\":true,\"rewards\":[{\"type\":\"mcaquests:item\","
                        + "\"item\":\"minecraft:diamond\",\"count\":1}]")));
        assertFalse(InstitutionalCommissionBridge.supportedDefinition(parse(
                "\"institutional_commission\":true,\"rewards\":[{\"type\":\"mcaquests:item\","
                        + "\"item\":\"minecraft:emerald\",\"count\":6}]")),
                "institutional authoring must include the legacy-provider tripwire");
    }

    @Test
    void serviceGateMustBePositiveAndMandatory() {
        var service = new InstitutionalServiceAvailableCondition();
        var other = new IsPlayerSpouseCondition();
        assertTrue(InstitutionalCommissionBridge.hasMandatoryServiceGate(service));
        assertTrue(InstitutionalCommissionBridge.hasMandatoryServiceGate(
                new AllOfCondition(List.of(other, service))));
        assertFalse(InstitutionalCommissionBridge.hasMandatoryServiceGate(
                new AnyOfCondition(List.of(other, service))));
    }

    @Test
    void unknownConditionTypeIsAHardCodecFailure() {
        assertTrue(ConditionTypes.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"type\":\"mcaquests:provider_too_old_tripwire\"}")).error().isPresent());
        assertTrue(QuestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"id\":\"ultima:test\",\"giver\":{},\"dialogue\":{},"
                        + "\"conditions\":{\"type\":\"mcaquests:provider_too_old_tripwire\"}}"))
                .error().isPresent(), "the owning definition must preserve the strict condition error");
    }

    @Test
    void institutionalAbandonCallsDurableOwnerCancellationBeforeRemoval() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/otectus/mcaquests/quest/QuestManager.java"));
        int abandon = source.indexOf("public static boolean abandon(ServerPlayer player, ActiveQuest active");
        int cancelled = source.indexOf("InstitutionalCommissionBridge.cancelled", abandon);
        int removal = source.indexOf("data.remove(active)", abandon);
        assertTrue(abandon >= 0 && cancelled > abandon && removal > cancelled,
                "owner cancellation must commit before the native quest is removed");
    }

    private static String gate() {
        return "\"conditions\":{\"type\":\"mcaquests:institutional_service_available\"}";
    }

    private static QuestDefinition parse(String tail) {
        return QuestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"id\":\"ultima:test\",\"giver\":{},\"dialogue\":{}," + tail + "}"))
                .result().orElseThrow();
    }
}
