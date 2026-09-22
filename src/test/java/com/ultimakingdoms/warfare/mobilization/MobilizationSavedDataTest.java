package com.ultimakingdoms.warfare.mobilization;

import com.ultimakingdoms.compat.recruits.RecruitsMobilization.Orders;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MobilizationSavedDataTest {
    @Test void futureSchemaIsPreservedReadOnly(){CompoundTag tag=new CompoundTag();tag.putInt("Schema",9);tag.putString("Payload","{\"future\":true}");
        var data=MobilizationSavedData.load(tag);assertFalse(data.writable());assertEquals(tag,data.save(new CompoundTag()));}
    @Test void activeUnitCannotAcquireDuplicateLease(){UUID unit=UUID.randomUUID(),owner=UUID.randomUUID(),group=UUID.randomUUID();var state=new MobilizationSavedData.State();
        var first=lease(UUID.randomUUID(),unit,owner,group,MobilizationSavedData.Phase.ACTIVE_PENDING_NATIVE_SAVE);
        var second=lease(UUID.randomUUID(),unit,owner,group,MobilizationSavedData.Phase.DURABLE_ACTIVE);state.leases.put(first.id().toString(),first);state.leases.put(second.id().toString(),second);
        var loaded=load(state);assertFalse(loaded.writable(),"duplicate live leases must preserve the file read-only");}
    @Test void restoredTombstoneAllowsAReplacementWithoutLosingReceipt(){UUID unit=UUID.randomUUID(),owner=UUID.randomUUID(),group=UUID.randomUUID();var state=new MobilizationSavedData.State();
        var old=lease(UUID.randomUUID(),unit,owner,group,MobilizationSavedData.Phase.RESTORED);var current=lease(UUID.randomUUID(),unit,owner,group,MobilizationSavedData.Phase.PREPARED);
        state.leases.put(old.id().toString(),old);state.leases.put(current.id().toString(),current);var loaded=load(state);assertTrue(loaded.writable());assertEquals(2,loaded.leases().size());
        assertTrue(loaded.restorableLease(old.id()).isEmpty(),"terminal tombstone must never become a restoration write source");
        assertEquals(current.id(),loaded.restorableLease(current.id()).orElseThrow().id());assertEquals(current.id(),loaded.activeUnit(unit).orElseThrow().id());}
    @Test void leaseActorMustRemainThePersistedNativeOwner(){UUID unit=UUID.randomUUID(),owner=UUID.randomUUID(),other=UUID.randomUUID(),group=UUID.randomUUID();
        Orders before=new Orders(unit,owner,group,0,true,false,false,false,false,false,false,false,false,0,0,0,0,0,0,null);
        Orders applied=new Orders(unit,owner,group,0,true,false,true,false,false,false,true,false,false,1,2,3,0,0,0,null);
        assertThrows(IllegalArgumentException.class,()->new MobilizationSavedData.Lease(UUID.randomUUID(),unit,other,"native","ultima_kingdoms:test",
                MobilizationDoctrine.DEFENSE,before,applied,10,100,MobilizationSavedData.Phase.PREPARED,"test"));}
    @Test void restorationAwaitingEntityPersistenceSurvivesRestartAsNonterminal(){UUID unit=UUID.randomUUID(),owner=UUID.randomUUID(),group=UUID.randomUUID();
        var pending=lease(UUID.randomUUID(),unit,owner,group,MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE);var state=new MobilizationSavedData.State();
        state.leases.put(pending.id().toString(),pending);var loaded=load(state);assertTrue(loaded.writable());assertFalse(pending.terminal());
        assertEquals(MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE,loaded.restorableLease(pending.id()).orElseThrow().phase());
        assertEquals(pending.id(),loaded.activeUnit(unit).orElseThrow().id());}
    private static MobilizationSavedData load(MobilizationSavedData.State state){CompoundTag tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Payload",MobilizationSavedData.JSON.toJson(state));return MobilizationSavedData.load(tag);}
    private static MobilizationSavedData.Lease lease(UUID id,UUID unit,UUID owner,UUID group,MobilizationSavedData.Phase phase){Orders before=new Orders(unit,owner,group,0,true,false,false,false,false,false,false,false,false,0,0,0,0,0,0,null);
        Orders applied=new Orders(unit,owner,group,0,true,false,true,false,false,false,true,false,false,1,2,3,0,0,0,null);
        return new MobilizationSavedData.Lease(id,unit,owner,"native","ultima_kingdoms:test",MobilizationDoctrine.DEFENSE,before,applied,10,100,phase,"test");}
}
