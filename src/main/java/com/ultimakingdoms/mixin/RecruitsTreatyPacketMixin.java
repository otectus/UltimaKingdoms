package com.ultimakingdoms.mixin;

import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.talhanation.recruits.network.MessageAnswerTreaty", remap = false)
public abstract class RecruitsTreatyPacketMixin {
    @Inject(method = "executeServerSide", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void ultima$authorizeTreaty(NetworkEvent.Context context, CallbackInfo callback) {
        if (!com.ultimakingdoms.compat.recruits.RecruitsPacketGuard.authorizeTreaty(this, context)) callback.cancel();
    }
}
