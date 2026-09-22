package com.ultimakingdoms.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Cancelled native Start must not publish a siege or force civilian hiding afterward. */
@Pseudo
@Mixin(targets="com.talhanation.recruits.ClaimEvents",remap=false)
public abstract class RecruitsDetectionMixin {
    /** Guard the caller's whole zero-health completion branch, including removal and civilian cleanup. */
    @Redirect(method="tickActiveSieges",at=@At(value="INVOKE",target="Lcom/talhanation/recruits/world/RecruitsClaim;getHealth()I",ordinal=1),remap=false,require=1)
    private int ultima$eligibleCompletion(@Coerce Object claim,ServerLevel level) {
        try {
            int health=(int)claim.getClass().getMethod("getHealth").invoke(claim);
            return com.ultimakingdoms.compat.recruits.RecruitsEvents.allowed(level,claim)?health:Math.max(1,health);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native siege completion unavailable",failure); }
    }
    @Redirect(method="tickDetection",at=@At(value="INVOKE",target="Lcom/talhanation/recruits/ClaimEvents;sendVillagersHome(Lnet/minecraft/server/level/ServerLevel;Lcom/talhanation/recruits/world/RecruitsClaim;)V"),remap=false,require=1)
    private void ultima$eligibleCivilianResponse(ServerLevel level,@Coerce Object claim) {
        if (!eligible(claim)) return;
        try {
            var method=Class.forName("com.talhanation.recruits.ClaimEvents").getDeclaredMethod("sendVillagersHome",ServerLevel.class,claim.getClass());
            method.setAccessible(true);method.invoke(null,level,claim);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native civilian siege response unavailable",failure); }
    }
    @Redirect(method="tickDetection",at=@At(value="INVOKE",target="Lcom/talhanation/recruits/world/RecruitsClaimManager;broadcastClaimUpdateToAll(Lnet/minecraft/server/level/ServerLevel;Lcom/talhanation/recruits/world/RecruitsClaim;)V"),remap=false,require=1)
    private void ultima$eligibleSiegeBroadcast(@Coerce Object manager,ServerLevel level,@Coerce Object claim) {
        if (!eligible(claim)) return;
        try { manager.getClass().getMethod("broadcastClaimUpdateToAll",ServerLevel.class,claim.getClass()).invoke(manager,level,claim); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native siege broadcast unavailable",failure); }
    }
    private static boolean eligible(Object claim) {
        try { return (boolean)claim.getClass().getField("isUnderSiege").get(claim); }
        catch (ReflectiveOperationException failure) { return false; }
    }
}
