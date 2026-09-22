package com.ultimakingdoms.compat.recruits;

import com.ultimakingdoms.evolution.RecruitTransferData;
import java.util.*;

/** Exact native count-write sequence; all other writes durably invalidate pending automatic recovery. */
public final class TransferAccounting implements AutoCloseable {
    private record Change(UUID owner,int delta) { }
    private static final ThreadLocal<TransferAccounting> ACTIVE=new ThreadLocal<>();
    private static final ThreadLocal<UUID> NATIVE_UNIT=new ThreadLocal<>();
    private final UUID unit;
    private final ArrayDeque<Change> expected=new ArrayDeque<>();
    private final TransferAccounting previous;
    private boolean recovery;
    private TransferAccounting(UUID unit,UUID first,int delta,UUID second,int other) {
        this.unit=unit;previous=ACTIVE.get();
        if(delta!=0)expected.add(new Change(first,delta));if(other!=0)expected.add(new Change(second,other));ACTIVE.set(this);
    }
    public static TransferAccounting expect(UUID unit,UUID first,int delta,UUID second,int other) { return new TransferAccounting(unit,first,delta,second,other); }
    public static TransferAccounting recovery(UUID unit,UUID first,int delta,UUID second,int other) {
        var scope=expect(unit,first,delta,second,other);scope.recovery=true;return scope;
    }
    /** Called only at the exact native hire/disband accounting instruction, after cancellable events. */
    public static void nativeWrite(net.minecraft.world.entity.Entity unit,Object manager,String method,UUID owner,int amount) {
        UUID previous=NATIVE_UNIT.get();NATIVE_UNIT.set(unit.getUUID());
        try {manager.getClass().getMethod(method,UUID.class,int.class).invoke(manager,owner,amount);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Native transfer accounting failed",failure);}
        finally{if(previous==null)NATIVE_UNIT.remove();else NATIVE_UNIT.set(previous);}
    }
    public static void changing(UUID owner,int delta) {
        var server=net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if(server==null || !server.isSameThread())return;
        var active=ACTIVE.get();UUID allowed=null;
        if(active!=null && (active.recovery||active.unit.equals(NATIVE_UNIT.get())) && Objects.equals(active.expected.peek(),new Change(owner,delta))) {active.expected.remove();allowed=active.unit;}
        RecruitTransferData.get(server).accountingChanging(server,owner,allowed);
    }
    @Override public void close(){if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
}
