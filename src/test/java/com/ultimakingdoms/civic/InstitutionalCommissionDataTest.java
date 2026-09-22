package com.ultimakingdoms.civic;

import com.google.gson.Gson;
import com.ultimakingdoms.api.politics.Politics;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionalCommissionDataTest {
    @Test void honorReservationsRetainAcceptedWorkAndExpireOnlyUnacceptedOffers() {
        var accepted=contract();var offer=contract().accepted(null);
        var tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Contracts",new Gson().toJson(List.of(accepted,offer)));
        var loaded=InstitutionalCommissionData.load(tag);
        assertEquals(2,loaded.reservedHonors(Set.of(),1199));
        assertEquals(1,loaded.reservedHonors(Set.of(),1200));
        assertEquals(0,loaded.reservedHonors(Set.of(accepted.id()),1200));
        assertEquals(1,loaded.reservedHonors(Set.of(accepted.id()),1199));
        tag.putInt("Schema",99);
        assertEquals(8192,InstitutionalCommissionData.load(tag).reservedHonors(Set.of(),1200));
    }
    private static InstitutionalCommissionData.Contract contract() {
        var terms=new Politics.Definition(1,"honor","workshop",List.of(),Set.of(),List.of(),Set.of(),0,1,false,"");
        return new InstitutionalCommissionData.Contract(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                "test:guild","test:kingdom","test:quest",3,2,"building","legal",1200,UUID.randomUUID(),"test:honor",terms);
    }
    @Test void acceptedInstanceAndFrozenHonorSurviveRestart() {
        var contract=contract();var tag=new CompoundTag();tag.putInt("Schema",1);
        tag.putString("Contracts",new Gson().toJson(List.of(contract)));
        var loaded=InstitutionalCommissionData.load(tag);
        assertTrue(loaded.writable());assertEquals(contract,loaded.get(contract.id()).orElseThrow());
        var obligation=loaded.obligations("test:guild").get(0);assertEquals(contract.id(),obligation.id());assertEquals(contract.instance(),obligation.nativeInstance());
        assertTrue(loaded.obligations("test:other").isEmpty());
        assertEquals(tag,loaded.save(new CompoundTag()));
        assertEquals(contract,InstitutionalCommissionData.load(loaded.save(new CompoundTag())).get(contract.id()).orElseThrow());
    }
    @Test void duplicateInvalidAndFutureContractsPreserveOriginalPayload() {
        var contract=contract();var duplicates=new CompoundTag();duplicates.putInt("Schema",1);
        duplicates.putString("Contracts",new Gson().toJson(List.of(contract,contract)));
        var future=new CompoundTag();future.putInt("Schema",99);future.putString("Unknown","keep");
        var invalid=duplicates.copy();invalid.putString("Contracts","[{\"id\":null}]");
        for(var tag:List.of(duplicates,future,invalid)) {
            var loaded=InstitutionalCommissionData.load(tag);assertFalse(loaded.writable());
            assertEquals(tag,loaded.save(new CompoundTag()));
        }
    }
}
