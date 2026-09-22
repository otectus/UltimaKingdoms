package dev.otectus.mcaquests.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow access to vanilla's protected single-player save used by the receipt durability fence. */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Invoker("save")
    void mcaquests$savePlayer(ServerPlayer player);
}
