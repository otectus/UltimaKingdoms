package com.ultimakingdoms.compat.quests.receipts;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestReceiptDataTest {
    private QuestReceiptData.Intent intent(List<QuestReceiptData.Effect> effects) {
        return new QuestReceiptData.Intent(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"test:quest",UUID.randomUUID(),null,"minecraft:overworld",null,effects,false);
    }
    @Test void terminalNoEffectPolicyAndFrozenRoleAreValidDurableIntents() {
        var ignored=intent(List.of());assertDoesNotThrow(ignored::validate);assertTrue(ignored.completed().delivered());
        var accepted=intent(List.of(new QuestReceiptData.Effect("test:guild",20,true)));accepted.validate();
        assertEquals(accepted.effects(),accepted.completed().effects());assertEquals(accepted.effectReceipt(),accepted.completed().effectReceipt());
    }
    @Test void duplicateRecipientsAndOversizedFanoutAreRejected() {
        var effect=new QuestReceiptData.Effect("test:guild",10,false);
        assertThrows(IllegalArgumentException.class,()->intent(List.of(effect,effect)).validate());
        var effects=new ArrayList<QuestReceiptData.Effect>();for(int i=0;i<129;i++)effects.add(new QuestReceiptData.Effect("test:guild_"+i,10,false));
        assertThrows(IllegalArgumentException.class,()->intent(effects).validate());
    }
    @Test void civilianAndLegacyBindingsSurviveReceiptRestart() {
        for(String token:List.of(UUID.randomUUID().toString(),"r3:"+UUID.randomUUID())) {
            var i=intent(List.of());var value=new QuestReceiptData.Intent(i.epoch(),i.receipt(),i.player(),i.quest(),i.giver(),null,i.dimension(),null,List.of(),false,token);
            value.validate();var tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Payload",new com.google.gson.Gson().toJson(List.of(value)));
            var loaded=QuestReceiptData.load(tag);assertTrue(loaded.writable());assertEquals(token,loaded.get(value.key()).orElseThrow().institutionalBinding());
        }
    }
    @Test void unsupportedPayloadIsPreservedExactly() {
        var raw=new CompoundTag();raw.putInt("Schema",99);raw.putString("Payload","future");
        var loaded=QuestReceiptData.load(raw);assertFalse(loaded.writable());assertEquals(raw,loaded.save(new CompoundTag()));
    }
}
