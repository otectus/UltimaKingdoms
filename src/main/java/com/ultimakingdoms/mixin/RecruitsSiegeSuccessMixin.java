package com.ultimakingdoms.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native tick checks zero health even after its Tick event was cancelled. */
@Pseudo
@Mixin(targets="com.talhanation.recruits.world.RecruitsClaim",remap=false)
public abstract class RecruitsSiegeSuccessMixin {
    @Inject(method="setSiegeSuccess",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void ultima$eligibleSuccess(ServerLevel level,CallbackInfo callback) {
        if(!com.ultimakingdoms.compat.recruits.RecruitsEvents.allowed(level,this))callback.cancel();
    }
}
