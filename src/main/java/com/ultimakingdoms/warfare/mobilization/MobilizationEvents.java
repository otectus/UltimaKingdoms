package com.ultimakingdoms.warfare.mobilization;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.compat.recruits.RecruitsMobilization;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.*;

@Mod.EventBusSubscriber(modid=UltimaKingdomsApi.MOD_ID)
public final class MobilizationEvents {
    private static final Map<MinecraftServer,MobilizationService> SERVICES=new WeakHashMap<>();
    private record Queued(WeakReference<Entity> entity,long joinedAt) { }
    private static final Map<MinecraftServer,ArrayDeque<Queued>> QUEUES=new WeakHashMap<>();
    private MobilizationEvents(){ }
    @SubscribeEvent public static void started(ServerStartedEvent event){SERVICES.put(event.getServer(),new MobilizationService(event.getServer()));QUEUES.put(event.getServer(),new ArrayDeque<>());}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){SERVICES.remove(event.getServer());QUEUES.remove(event.getServer());}
    @SubscribeEvent public static void join(EntityJoinLevelEvent event){if(event.getLevel().isClientSide()||!RecruitsMobilization.isRecruit(event.getEntity()))return;
        MinecraftServer server=event.getEntity().getServer();if(server==null)return;ArrayDeque<Queued> queue=QUEUES.get(server);if(queue!=null&&queue.size()<4096)
            queue.add(new Queued(new WeakReference<>(event.getEntity()),event.getEntity().level().getGameTime()));}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){if(event.phase!=TickEvent.Phase.END)return;MobilizationService service=SERVICES.get(event.getServer());if(service==null)return;
        int checked=service.recoverLoadedAtStartup(4);ArrayDeque<Queued> queue=QUEUES.get(event.getServer());
        for(int i=checked;i<8&&queue!=null&&!queue.isEmpty();i++){Queued queued=queue.remove();Entity unit=queued.entity().get();if(unit!=null&&!unit.isRemoved())service.loaded(unit,queued.joinedAt(),false);}
        if(event.getServer().getTickCount()%20==0)service.tick();}
    public static MobilizationService get(MinecraftServer server){MobilizationService service=SERVICES.get(server);if(service==null)throw new IllegalArgumentException("Mobilization service unavailable.");return service;}
}
