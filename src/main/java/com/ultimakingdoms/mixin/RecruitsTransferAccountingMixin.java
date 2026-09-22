package com.ultimakingdoms.mixin;

import com.ultimakingdoms.compat.recruits.TransferAccounting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Pseudo
@Mixin(targets="com.talhanation.recruits.world.RecruitsPlayerUnitManager",remap=false)
public abstract class RecruitsTransferAccountingMixin {
    @Inject(method="addRecruits",at=@At("HEAD"),remap=false)
    private void ultima$add(UUID owner,int amount,CallbackInfo ci){TransferAccounting.changing(owner,amount);}
    @Inject(method="removeRecruits",at=@At("HEAD"),remap=false)
    private void ultima$remove(UUID owner,int amount,CallbackInfo ci){TransferAccounting.changing(owner,-amount);}
    @Inject(method="setRecruitCount",at=@At("HEAD"),remap=false)
    private void ultima$set(Player owner,int amount,CallbackInfo ci){TransferAccounting.changing(owner.getUUID(),Integer.MIN_VALUE);}
    @Inject(method="recountRecruits",at=@At("HEAD"),remap=false)
    private void ultima$recount(MinecraftServer server,UUID owner,CallbackInfo ci){TransferAccounting.changing(owner,Integer.MIN_VALUE);}
}
