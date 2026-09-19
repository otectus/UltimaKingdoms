package com.ultimakingdoms;

import com.ultimakingdoms.api.ApiBootstrap;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.compat.mca.McaCompat;
import com.ultimakingdoms.config.UltimaKingdomsConfig;
import com.ultimakingdoms.core.KingdomsServiceImpl;
import com.ultimakingdoms.data.DefinitionRegistry;
import com.ultimakingdoms.presentation.Presentation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingConversionEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod(UltimaKingdomsApi.MOD_ID)
public final class UltimaKingdoms {
    public static final DefinitionRegistry DEFINITIONS = new DefinitionRegistry();
    private static final int MAX_PENDING_CHUNKS = 4_096;
    private static final Map<MinecraftServer, RuntimeState> RUNTIMES = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, Set<PendingChunk>> PENDING_CHUNKS = new ConcurrentHashMap<>();

    public UltimaKingdoms() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, UltimaKingdomsConfig.SPEC);
        Presentation.init(FMLJavaModLoadingContext.get().getModEventBus());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(DEFINITIONS.reloadListener());
    }

    @SubscribeEvent
    public void serverAboutToStart(ServerAboutToStartEvent event) {
        DEFINITIONS.commitPending();
        PENDING_CHUNKS.computeIfAbsent(event.getServer(), ignored -> ConcurrentHashMap.newKeySet());
    }

    @SubscribeEvent
    public void serverStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        KingdomsServiceImpl service = new KingdomsServiceImpl(server, DEFINITIONS);
        ApiBootstrap.attach(server, service);
        Registration mca = () -> { };
        try {
            mca = McaCompat.attach(server, service);
            RUNTIMES.put(server, new RuntimeState(service, mca));
            Set<PendingChunk> pending = PENDING_CHUNKS.remove(server);
            if (pending != null) {
                pending.forEach(chunk -> {
                    ServerLevel level = server.getLevel(chunk.dimension());
                    if (level != null) service.enqueueChunk(level, new ChunkPos(chunk.chunk()));
                });
            }
            enqueueLoadedSpawnChunks(server, service);
        } catch (RuntimeException exception) {
            PENDING_CHUNKS.remove(server);
            RUNTIMES.remove(server);
            try {
                mca.close();
            } finally {
                ApiBootstrap.detach(server, service);
            }
            throw exception;
        }
    }

    @SubscribeEvent
    public void datapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        MinecraftServer server = event.getPlayerList().getServer();
        DEFINITIONS.commitPending();
        RuntimeState runtime = RUNTIMES.get(server);
        if (runtime != null) runtime.service().definitionsReloaded();
    }

    @SubscribeEvent
    public void chunkLoaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RuntimeState runtime = RUNTIMES.get(level.getServer());
        if (runtime != null) {
            runtime.service().enqueueChunk(level, event.getChunk().getPos());
            return;
        }
        Set<PendingChunk> pending = PENDING_CHUNKS.computeIfAbsent(
                level.getServer(), ignored -> ConcurrentHashMap.newKeySet());
        if (pending.size() < MAX_PENDING_CHUNKS) {
            pending.add(new PendingChunk(level.dimension(), event.getChunk().getPos().toLong()));
        }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        RuntimeState runtime = RUNTIMES.get(event.getServer());
        if (runtime != null) runtime.service().tickDiscovery();
    }

    @SubscribeEvent
    public void entityJoined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof LivingEntity)
                || event.getEntity() instanceof Player) {
            return;
        }
        RuntimeState runtime = RUNTIMES.get(level.getServer());
        if (runtime != null) runtime.service().observeEntity(event.getEntity());
    }

    @SubscribeEvent
    public void livingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player) return;
        int interval = UltimaKingdomsConfig.CIVIC_EVIDENCE_INTERVAL.get();
        if (Math.floorMod(entity.tickCount + entity.getId(), interval) != 0) return;
        RuntimeState runtime = RUNTIMES.get(level.getServer());
        if (runtime != null) runtime.service().observeEntity(entity);
    }

    @SubscribeEvent
    public void livingConverted(LivingConversionEvent.Post event) {
        MinecraftServer server = event.getEntity().getServer();
        RuntimeState runtime = server == null ? null : RUNTIMES.get(server);
        if (runtime != null) runtime.service().copyIdentity(event.getEntity(), event.getOutcome());
    }

    @SubscribeEvent
    public void serverStopped(ServerStoppedEvent event) {
        PENDING_CHUNKS.remove(event.getServer());
        RuntimeState runtime = RUNTIMES.remove(event.getServer());
        if (runtime != null) {
            try {
                runtime.mca().close();
            } finally {
                ApiBootstrap.detach(event.getServer(), runtime.service());
            }
        }
        DEFINITIONS.clear();
    }

    private record RuntimeState(KingdomsServiceImpl service, Registration mca) {
    }

    private static void enqueueLoadedSpawnChunks(MinecraftServer server, KingdomsServiceImpl service) {
        for (ServerLevel level : server.getAllLevels()) {
            ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
            for (int x = spawn.x - 4; x <= spawn.x + 4; x++) {
                for (int z = spawn.z - 4; z <= spawn.z + 4; z++) {
                    if (level.hasChunk(x, z)) service.enqueueChunk(level, new ChunkPos(x, z));
                }
            }
        }
    }

    private record PendingChunk(ResourceKey<Level> dimension, long chunk) {
    }
}
