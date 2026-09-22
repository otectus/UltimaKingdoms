package com.ultimakingdoms.factions;

import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.factions.UltimaFactionsApi;
import com.ultimakingdoms.compat.reputation.ReputationBridge;
import com.ultimakingdoms.factions.command.FactionCommands;
import com.ultimakingdoms.factions.config.FactionConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.WeakHashMap;

public final class FactionsRuntime {
    private static final AtomicBoolean GLOBAL_HOOKS=new AtomicBoolean();
    private static final Map<MinecraftServer,FactionServiceImpl> SERVICES=
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private FactionsRuntime() {}

    public static void registerGlobalHooks() {
        if(GLOBAL_HOOKS.compareAndSet(false,true)) {
            FactionConfig.register();
            MinecraftForge.EVENT_BUS.register(FactionCommands.class);
            MinecraftForge.EVENT_BUS.register(FactionsRuntime.class);
        }
    }

    public static Registration attach(MinecraftServer server, KingdomsService kingdoms) {
        Objects.requireNonNull(server,"server");Objects.requireNonNull(kingdoms,"kingdoms");
        FactionServiceImpl service=new FactionServiceImpl(server,kingdoms);
        SERVICES.put(server,service);
        UltimaFactionsApi.attach(server,service);
        Registration reputation=ReputationBridge.attach(server,kingdoms,service);
        return ()->{reputation.close();SERVICES.remove(server,service);UltimaFactionsApi.detach(server,service);};
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = event.getServer().overworld().getGameTime();
        if (Math.floorMod(now, 1200L) != 0L) return;
        FactionServiceImpl service = SERVICES.get(event.getServer());
        if (service != null) service.pruneReceipts(now, FactionConfig.RECEIPT_RETENTION_TICKS.get());
    }
}
