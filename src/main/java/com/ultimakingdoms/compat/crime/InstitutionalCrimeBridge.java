package com.ultimakingdoms.compat.crime;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.*;

/** Only the explicit R2 provider query can authorize institutional work. */
public final class InstitutionalCrimeBridge {
    public record View(boolean allowed,String reason,String fingerprint,List<String> restitution) {
        public View { restitution=List.copyOf(restitution); }
    }
    private InstitutionalCrimeBridge() { }
    public static View workshop(ServerPlayer player,Entity giver) {
        if(!player.getServer().isSameThread())throw new IllegalStateException("Legal queries require server thread");
        try {
            var api=Class.forName("dev.otectus.mcacrime.api.InstitutionalServiceApi");
            Object view=api.getMethod("workshop",ServerPlayer.class,Entity.class).invoke(null,player,giver);
            String status=(String)value(view,"status"),reason=(String)value(view,"reason"),fingerprint=(String)value(view,"fingerprint");
            if(fingerprint==null||fingerprint.length()>128||reason==null||reason.length()>256)return unavailable();
            List<String> restitution=new ArrayList<>();
            for(Object contract:(List<?>)value(view,"restitution")) {
                if(restitution.size()==8)break;
                if(!player.getUUID().equals(value(contract,"offender")))return unavailable();
                String line=value(contract,"task")+": "+value(contract,"completedUnits")+"/"+value(contract,"requiredUnits")+" ("+value(contract,"state")+")";
                restitution.add(line.substring(0,Math.min(256,line.length())));
            }
            return new View(status.equals("allowed"),status.equals("refused")?"civic.legal_service_suspended":status.equals("allowed")?"":"civic.legal_unavailable",fingerprint,restitution);
        } catch(ReflectiveOperationException|LinkageError|RuntimeException failure) { return unavailable(); }
    }
    private static Object value(Object record,String name) throws ReflectiveOperationException { return record.getClass().getMethod(name).invoke(record); }
    private static View unavailable() { return new View(false,"civic.legal_unavailable","",List.of()); }
}
