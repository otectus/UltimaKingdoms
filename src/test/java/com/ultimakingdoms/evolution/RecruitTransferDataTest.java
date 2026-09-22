package com.ultimakingdoms.evolution;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecruitTransferDataTest {
    private RecruitTransferData.Transfer offered(){return new RecruitTransferData.Transfer(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),true,1200,72000,1,RecruitTransferData.Phase.OFFERED,null,"Frozen consent");}
    @Test void recipientConsentCannotBeReplacedBySourceOrStaleRevision(){
        var t=offered();assertThrows(IllegalArgumentException.class,()->t.consent(t.owner(),1,100));
        assertThrows(IllegalArgumentException.class,()->t.consent(t.recipient(),2,100));
        var accepted=t.consent(t.recipient(),1,100);assertEquals(RecruitTransferData.Phase.CONSENTED,accepted.phase());
        assertThrows(IllegalArgumentException.class,()->accepted.consent(t.recipient(),2,100));
    }
    @Test void malformedReconciliationCannotEraseOriginalIntent(){
        var json=RecruitTransferData.JSON.toJsonTree(offered()).getAsJsonObject();json.addProperty("phase","RECONCILED");
        json.add("reconciliation",RecruitTransferData.JSON.toJsonTree(new RecruitTransferData.Reconciliation(UUID.randomUUID(),"b".repeat(64),"Native state reviewed",200,"observed")));
        var tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Transfers","["+json+"]");
        var loaded=RecruitTransferData.load(tag);assertFalse(loaded.writable());assertEquals(tag,loaded.save(new CompoundTag()));
    }
    @Test void duplicatedIntentAndFutureSchemaRemainPreserved(){
        var t=offered();var tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Transfers",RecruitTransferData.JSON.toJson(List.of(t,t)));
        assertFalse(RecruitTransferData.load(tag).writable());
        tag.putInt("Schema",99);var loaded=RecruitTransferData.load(tag);assertFalse(loaded.writable());assertEquals(tag,loaded.save(new CompoundTag()));
    }
}
