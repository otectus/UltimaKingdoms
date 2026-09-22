package dev.otectus.mcaquests.state;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcaquests.api.QuestCompletionReceipt;
import dev.otectus.mcaquests.quest.QuestDefinition;
import dev.otectus.mcaquests.quest.reward.ItemReward;
import dev.otectus.mcaquests.support.TestBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstitutionalCommissionPersistenceTest {
    private static final String BINDING = "11111111-2222-3333-4444-555555555555";
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final ResourceLocation CONSUMER = new ResourceLocation("ultima_kingdoms", "civic");

    static { TestBootstrap.ensureBootstrapped(); }

    @Test
    void bindingAcceptedTermsAndReceiptSurvivePlayerDataRoundTrip() {
        QuestDefinition accepted = definition(6, true);
        ActiveQuest active = active(accepted);
        assertTrue(active.bindInstitutional(BINDING, accepted).isPresent());

        ActiveQuest restoredActive = ActiveQuest.load(active.save());
        assertEquals(BINDING, restoredActive.institutionalBinding());
        assertEquals(6, ((ItemReward) restoredActive.resolve(definition(9, true)).rewards().get(0)).count(),
                "the accepted snapshot, rather than the changed live payout, must resolve");
        assertFalse(restoredActive.institutionalDefinitionMatches(definition(9, true)));
        assertTrue(restoredActive.institutionalDefinitionMatches(accepted));

        PlayerQuestData acceptedData = new PlayerQuestData();
        acceptedData.add(restoredActive);
        PlayerQuestData acceptedDisk = new PlayerQuestData();
        acceptedDisk.load(acceptedData.save());
        assertTrue(CompletionReceiptDurability.containsActiveSnapshot(acceptedDisk, restoredActive),
                "acceptance verification must find the exact instance, binding, and frozen terms");
        acceptedDisk.remove(acceptedDisk.active().get(0));
        assertFalse(CompletionReceiptDurability.containsActiveSnapshot(acceptedDisk, restoredActive));

        PlayerQuestData data = new PlayerQuestData();
        data.readCompletionReceipts(CONSUMER, 8, 1L);
        QuestCompletionReceipt receipt = data.captureCompletionReceipt(PLAYER, restoredActive, 20L);
        data.markCompletionReceiptDurable(receipt.receiptId());
        PlayerQuestData reloaded = new PlayerQuestData();
        reloaded.load(data.save());
        assertEquals(BINDING, reloaded.readCompletionReceipts(CONSUMER, 8, 21L)
                .get(0).institutionalBinding());
    }

    @Test
    void legacyQuestAndLegacyDefinitionRemainOrdinary() {
        ActiveQuest legacy = active(definition(6, false));
        assertFalse(ActiveQuest.load(legacy.save()).isInstitutional());
        assertFalse(definition(6, false).institutionalCommission());
        assertFalse(legacy.institutionalShapeMatches(definition(6, true)),
                "an old ordinary active quest cannot acquire institutional terms after reload");
    }

    @Test
    void malformedInstitutionalMarkersRemainInstitutionalAndFailClosed() {
        ActiveQuest ordinary = active(definition(6, false));
        net.minecraft.nbt.CompoundTag emptyBinding = ordinary.save();
        emptyBinding.putString("institutional_binding", "");
        ActiveQuest emptyLoaded = ActiveQuest.load(emptyBinding);
        assertTrue(emptyLoaded.isInstitutional());
        assertFalse(emptyLoaded.institutionalShapeMatches(definition(6, true)));

        net.minecraft.nbt.CompoundTag oversized = ordinary.save();
        oversized.putString("institutional_binding", "x".repeat(129));
        ActiveQuest oversizedLoaded = ActiveQuest.load(oversized);
        assertTrue(oversizedLoaded.isInstitutional());
        assertTrue(oversizedLoaded.institutionalBinding().isEmpty());
        assertFalse(oversizedLoaded.institutionalShapeMatches(definition(6, true)));

        net.minecraft.nbt.CompoundTag wrongType = ordinary.save();
        wrongType.putInt("institutional_binding", 7);
        ActiveQuest wrongTypeLoaded = ActiveQuest.load(wrongType);
        assertTrue(wrongTypeLoaded.isInstitutional(),
                "a corrupt marker type cannot downgrade the persisted quest to ordinary");
        assertTrue(wrongTypeLoaded.institutionalBinding().isEmpty());
        assertFalse(wrongTypeLoaded.institutionalShapeMatches(definition(6, false)));
    }

    private static ActiveQuest active(QuestDefinition definition) {
        return ActiveQuest.create(definition.id(), UUID.randomUUID(), Component.literal("Issuer"), null,
                new ResourceLocation("minecraft", "overworld"), 10L, OptionalLong.of(10L),
                OptionalInt.empty(), definition.objectives().size(), null, null);
    }

    private static QuestDefinition definition(int emeralds, boolean institutional) {
        String json = "{\"id\":\"ultima:civic/lamplighters/workshop_lanterns\","
                + "\"giver\":{},\"dialogue\":{},\"institutional_commission\":" + institutional + ","
                + (institutional ? "\"conditions\":{\"type\":\"mcaquests:institutional_service_available\"}," : "")
                + "\"rewards\":[{\"type\":\"mcaquests:item\",\"item\":\"minecraft:emerald\","
                + "\"count\":" + emeralds + "}]}";
        return QuestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .result().orElseThrow();
    }
}
