package com.ultimakingdoms.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native detection otherwise registers an active siege even after Start is cancelled. */
@Pseudo
@Mixin(targets = "com.talhanation.recruits.world.RecruitsClaimManager", remap = false)
public abstract class RecruitsSiegeRegistrationMixin {
    @Inject(method = "addActiveSiege", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void ultima$registeredSiege(@Coerce Object claim, CallbackInfo callback) {
        try { if (!(boolean)claim.getClass().getField("isUnderSiege").get(claim)) callback.cancel(); }
        catch (ReflectiveOperationException failure) { callback.cancel(); }
    }
}
