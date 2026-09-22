package com.ultimakingdoms.warfare.mobilization;

import com.ultimakingdoms.api.politics.Politics;
import com.ultimakingdoms.api.politics.UltimaPoliticsApi;
import com.ultimakingdoms.compat.recruits.*;
import com.ultimakingdoms.warfare.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.*;

/** Temporary, owner-consented order composition. Native ownership and equipment remain untouched. */
public final class MobilizationService {
    private final MinecraftServer server;private final MobilizationSavedData data;
    private final List<UUID> startupRecovery;
    private final Map<UUID,Long> restorationPendingSince=new HashMap<>();
    private int startupRecoveryCursor,expirationCursor,verificationCursor;
    public MobilizationService(MinecraftServer server){this.server=server;data=MobilizationSavedData.get(server);startupRecovery=data.leases().stream()
            .filter(l->!l.terminal()).sorted(Comparator.comparing(MobilizationSavedData.Lease::createdAt).thenComparing(MobilizationSavedData.Lease::id))
            .map(MobilizationSavedData.Lease::unit).toList();}
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("Mobilization requires server thread");}
    private record Authority(String faction,String kingdom){ }
    private Authority authorize(ServerPlayer actor){
        thread();if(actor.getServer()!=server||!WarfareConfig.ENABLED.get()||!WarfareConfig.MILITARY.get()||!data.writable())throw new IllegalArgumentException("Mobilization unavailable.");
        RecruitsMilitary.ready(server);String faction=RecruitsMilitary.commandedFaction(actor);var mapping=WarfareRuntime.get(server).mapping(faction)
                .orElseThrow(()->new IllegalArgumentException("Native faction is not mapped to a recognized kingdom."));
        if(!UltimaPoliticsApi.get(server).authorized(actor,mapping.kingdom(),Politics.Permission.PROPOSE))
            throw new IllegalArgumentException("Kingdom military mandate unavailable.");return new Authority(faction,mapping.kingdom());
    }
    private void authorizeRestoration(ServerPlayer actor,MobilizationSavedData.Lease lease){
        thread();if(actor.getServer()!=server||actor.hasDisconnected()||!data.writable())throw new IllegalArgumentException("Mobilization restoration unavailable.");
        if(!actor.hasPermissions(2)&&!lease.actor().equals(actor.getUUID()))throw new IllegalArgumentException("Lease belongs to another commander.");
    }
    public String muster(ServerPlayer actor,UUID unitId,MobilizationDoctrine doctrine){
        Authority authority=authorize(actor);Entity unit=findLoaded(unitId);if(unit==null)return "Unit is not loaded; no chunk was loaded for this request.";
        if(unit.level()!=actor.level()||actor.distanceToSqr(unit)>32*32)return "Unit must be loaded within 32 blocks of its owner.";
        return muster(actor,unit,doctrine,authority);
    }
    public List<String> musterNearby(ServerPlayer actor,MobilizationDoctrine doctrine){
        Authority authority=authorize(actor);long active=data.leases().stream().filter(l->l.actor().equals(actor.getUUID())&&!l.terminal()).count();
        int room=(int)Math.max(0,WarfareConfig.UNIT_BUDGET.get()-active);if(room==0)return List.of("Mobilization budget reached.");
        List<String> result=new ArrayList<>();for(Entity unit:RecruitsMobilization.nearbyOwned(actor,room))result.add(muster(actor,unit,doctrine,authority));
        return result.isEmpty()?List.of("No loaded self-owned recruits within 32 blocks."):List.copyOf(result);
    }
    private String muster(ServerPlayer actor,Entity unit,MobilizationDoctrine doctrine,Authority authority){
        if(!RecruitsMobilization.ownedBy(unit,actor))return "Unit "+unit.getUUID()+" is not currently owned by the commander.";
        if(data.activeUnit(unit.getUUID()).isPresent())return "Unit "+unit.getUUID()+" already has a retained lease.";
        if(com.ultimakingdoms.evolution.RecruitTransferService.blocksMobilization(server,unit.getUUID()))return "Unit has a pending negotiated transfer; cancel or complete it before mustering.";
        long active=data.leases().stream().filter(l->l.actor().equals(actor.getUUID())&&!l.terminal()).count();if(active>=WarfareConfig.UNIT_BUDGET.get())return "Mobilization budget reached.";
        var before=RecruitsMobilization.snapshot(unit);if(before.owner()==null)return "Unit ownership identity is incomplete.";
        var applied=RecruitsMobilization.planned(unit,actor,doctrine);long now=actor.level().getGameTime();UUID leaseId=UUID.randomUUID();
        var lease=new MobilizationSavedData.Lease(leaseId,unit.getUUID(),actor.getUUID(),authority.faction(),authority.kingdom(),doctrine,before,applied,now,
                now+WarfareConfig.MOBILIZATION_TICKS.get(),MobilizationSavedData.Phase.PREPARED,"Lease durable; native order write not yet applied.");
        var next=data.snapshot();next.leases.put(leaseId.toString(),lease);next.revision++;if(!data.commit(server,next))return "Lease save failed; native orders unchanged.";
        try{
            if(!RecruitsMobilization.identity(unit,before)||!RecruitsMobilization.apply(unit,applied))return failedApply(lease,unit);
            return phase(lease,MobilizationSavedData.Phase.ACTIVE_PENDING_NATIVE_SAVE,
                    "Mobilized "+unit.getUUID()+" as "+doctrine+"; lease is durable, native entity save pending.");
        }catch(RuntimeException failure){return failedApply(lease,unit);}
    }
    private String failedApply(MobilizationSavedData.Lease lease,Entity unit){
        MobilizationSavedData.Phase phase;String detail;try{var current=RecruitsMobilization.snapshot(unit);
            if(current.equals(lease.before())){phase=MobilizationSavedData.Phase.RESTORED;detail="Native order write refused; original orders retained.";}
            else{phase=MobilizationSavedData.Phase.DIVERGED;detail="Native order write diverged; explicit recovery required.";}}
        catch(RuntimeException unavailable){phase=MobilizationSavedData.Phase.SUSPENDED;detail="Native provider unavailable after durable preparation.";}
        return phase(lease,phase,detail);
    }
    public String dismiss(ServerPlayer actor,UUID leaseId){thread();var lease=data.lease(leaseId).orElseThrow(()->new IllegalArgumentException("Lease unavailable."));authorizeRestoration(actor,lease);
        if(lease.terminal())return "Lease already restored; native orders were not changed.";
        Entity unit=findLoaded(lease.unit());if(unit==null)return phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING,"Dismissed; restoration awaits the unit loading.");
        return restore(lease,unit,false);
    }
    public String recover(ServerPlayer actor,UUID leaseId){thread();var lease=data.lease(leaseId).orElseThrow(()->new IllegalArgumentException("Lease unavailable."));authorizeRestoration(actor,lease);
        if(lease.terminal())return "Lease already restored; native orders were not changed.";
        Entity unit=findLoaded(lease.unit());if(unit==null)return "Unit is unloaded; recovery did not load its chunk.";return restore(lease,unit,true);
    }
    /** One bounded pass catches lease units that joined before ServerStarted installed the event queue. */
    public int recoverLoadedAtStartup(int budget){
        thread();int checked=0;while(checked<budget&&startupRecoveryCursor<startupRecovery.size()){
            Entity unit=findLoaded(startupRecovery.get(startupRecoveryCursor++));checked++;if(unit!=null&&!unit.isRemoved())loaded(unit,unit.level().getGameTime(),true);
        }return checked;
    }
    public void tick(){
        thread();long now=server.overworld().getGameTime();int budget=8;
        var confirmations=data.leases().stream().filter(l->l.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE
                &&!restorationPendingSince.containsKey(l.id())).sorted(Comparator.comparing(MobilizationSavedData.Lease::id)).toList();
        if(!confirmations.isEmpty()){int count=Math.min(budget,confirmations.size()),start=Math.floorMod(verificationCursor,confirmations.size());verificationCursor+=count;
            for(int i=0;i<count;i++){var lease=confirmations.get((start+i)%confirmations.size());Entity unit=findLoaded(lease.unit());budget--;if(unit!=null)loaded(unit,now,true);}}
        if(budget==0)return;var expired=data.leases().stream().filter(l->!l.terminal()&&l.expiresAt()<=now)
                .filter(l->l.phase()!=MobilizationSavedData.Phase.RESTORE_PENDING&&l.phase()!=MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE
                        &&l.phase()!=MobilizationSavedData.Phase.DIVERGED)
                .sorted(Comparator.comparing(MobilizationSavedData.Lease::expiresAt).thenComparing(MobilizationSavedData.Lease::id)).toList();
        if(expired.isEmpty())return;int count=Math.min(budget,expired.size()),start=Math.floorMod(expirationCursor,expired.size());expirationCursor+=count;
        for(int i=0;i<count;i++){var lease=expired.get((start+i)%expired.size());Entity unit=findLoaded(lease.unit());if(unit==null){
                phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING,"Expired; restoration awaits the unit loading.");continue;}restore(lease,unit,false);
        }
    }
    public void loaded(Entity unit,long observedAt,boolean startupRecovery){
        thread();var lease=data.activeUnit(unit.getUUID()).orElse(null);if(lease==null)return;
        if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE){long pendingSince=restorationPendingSince.getOrDefault(lease.id(),Long.MIN_VALUE);
            if(startupRecovery||observedAt>pendingSince)restorationPendingSince.remove(lease.id());}
        try{RecruitsMilitary.ready(server);var current=RecruitsMobilization.snapshot(unit);long now=unit.level().getGameTime();
            if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE){
                if(current.equals(lease.before())&&!restorationPendingSince.containsKey(lease.id())){
                    restorationPendingSince.remove(lease.id());phase(lease,MobilizationSavedData.Phase.RESTORED,"Entity reload confirmed the original orders persisted.");
                }else if(current.equals(lease.applied()))restore(lease,unit,false);
                else if(!current.equals(lease.before()))phase(lease,MobilizationSavedData.Phase.DIVERGED,"Reloaded orders diverged while restoration awaited native persistence.");
                return;
            }
            if(now>=lease.expiresAt()||lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING){restore(lease,unit,false);return;}
            if(current.equals(lease.applied()))phase(lease,MobilizationSavedData.Phase.DURABLE_ACTIVE,"Lease and native persisted orders confirmed after entity load.");
            else if((lease.phase()==MobilizationSavedData.Phase.PREPARED||lease.phase()==MobilizationSavedData.Phase.ACTIVE_PENDING_NATIVE_SAVE
                    ||lease.phase()==MobilizationSavedData.Phase.SUSPENDED)&&current.equals(lease.before()))
                phase(lease,MobilizationSavedData.Phase.RESTORED,"Lease recovered with original orders before native mutation became durable.");
            else phase(lease,MobilizationSavedData.Phase.DIVERGED,"Loaded native orders differ; explicit recovery is available.");
        }catch(RuntimeException unavailable){if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE)
                phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE,"Native provider unavailable; restoration still awaits entity persistence.");
            else phase(lease,MobilizationSavedData.Phase.SUSPENDED,"Native provider unavailable; lease retained without invented restoration.");}
    }
    private String restore(MobilizationSavedData.Lease lease,Entity unit,boolean explicit){
        var retained=data.restorableLease(lease.id());if(retained.isEmpty())return "Lease already restored; native orders were not changed.";lease=retained.get();
        try{
            if(!RecruitsMobilization.identity(unit,lease.before()))return phase(lease,MobilizationSavedData.Phase.DIVERGED,"Owner/group identity changed; restoration refused.");
            var current=RecruitsMobilization.snapshot(unit);
            if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE&&current.equals(lease.before())
                    &&!restorationPendingSince.containsKey(lease.id()))return phase(lease,MobilizationSavedData.Phase.RESTORED,"Entity reload confirmed the original orders persisted.");
            if(lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE&&!current.equals(lease.applied())&&!current.equals(lease.before()))
                return phase(lease,MobilizationSavedData.Phase.DIVERGED,"Orders changed while restoration awaited persistence; pending restoration did not overwrite them.");
            if(!explicit&&!current.equals(lease.applied())&&!current.equals(lease.before()))
                return phase(lease,MobilizationSavedData.Phase.DIVERGED,"Commander orders changed after mobilization; automatic restoration refused. Use explicit recovery to restore the saved orders.");
            String pending=phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE,"Original orders applied in memory; awaiting a confirming entity reload.");
            var durable=data.restorableLease(lease.id());if(durable.isEmpty()||durable.get().phase()!=MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE)
                return "Restoration intent save failed; native orders were not changed.";
            lease=durable.get();restorationPendingSince.put(lease.id(),unit.level().getGameTime());
            if(!current.equals(lease.before())&&!RecruitsMobilization.apply(unit,lease.before()))
                return phase(lease,MobilizationSavedData.Phase.DIVERGED,"Native provider refused restoration; explicit recovery remains available.");
            return pending;
        }catch(RuntimeException unavailable){return lease.phase()==MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE
                ?phase(lease,MobilizationSavedData.Phase.RESTORE_PENDING_NATIVE_SAVE,"Native provider unavailable; restoration still awaits entity persistence.")
                :phase(lease,MobilizationSavedData.Phase.SUSPENDED,"Native provider unavailable; restoration retained for retry.");}
    }
    private String phase(MobilizationSavedData.Lease lease,MobilizationSavedData.Phase phase,String detail){var next=data.snapshot();var current=next.leases.get(lease.id().toString());
        if(current==null)return "Lease unavailable.";if(current.phase()==phase&&current.detail().equals(detail))return detail;
        next.leases.put(lease.id().toString(),current.phase(phase,detail));next.revision++;return data.commit(server,next)?detail:"Lease status save failed; inspect before retrying.";}
    private Entity findLoaded(UUID id){for(var level:server.getAllLevels()){Entity entity=level.getEntity(id);if(entity!=null)return entity;}return null;}
    public List<MobilizationSavedData.Lease> deployments(ServerPlayer viewer,int offset,int limit){thread();if(viewer.getServer()!=server||viewer.hasDisconnected()||offset<0||offset>8192||limit<1||limit>64)throw new IllegalArgumentException("Invalid deployment query.");return data.leases().stream().filter(l->viewer.hasPermissions(2)||l.actor().equals(viewer.getUUID())).sorted(Comparator.comparing(MobilizationSavedData.Lease::createdAt).reversed().thenComparing(MobilizationSavedData.Lease::id)).skip(offset).limit(limit).toList();}
    public List<String> status(ServerPlayer viewer){thread();return data.leases().stream().filter(l->viewer.hasPermissions(2)||l.actor().equals(viewer.getUUID()))
            .sorted(Comparator.comparing(MobilizationSavedData.Lease::createdAt).reversed()).limit(32)
            .map(l->l.id()+" unit="+l.unit()+" "+l.doctrine()+" "+l.phase()+" expires="+l.expiresAt()+" "+l.detail()).toList();}
}
