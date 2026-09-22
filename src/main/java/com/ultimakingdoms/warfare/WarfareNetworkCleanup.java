package com.ultimakingdoms.warfare;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class WarfareNetworkCleanup {
    private WarfareNetworkCleanup() { }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { WarfareNetwork.forget(event.getEntity().getUUID()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { WarfareNetwork.clear(); }
}
