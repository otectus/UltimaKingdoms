package com.ultimakingdoms;

import com.ultimakingdoms.api.ApiBootstrap;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.compat.mca.McaCompat;
import com.ultimakingdoms.config.UltimaKingdomsConfig;
import com.ultimakingdoms.core.KingdomsServiceImpl;
import com.ultimakingdoms.data.DefinitionRegistry;
import com.ultimakingdoms.integration.IntegrationBootstrap;
import com.ultimakingdoms.integration.IntegrationConfig;
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
    public static final com.ultimakingdoms.politics.PoliticalDefinitions POLITICS = new com.ultimakingdoms.politics.PoliticalDefinitions();
    public static final DefinitionRegistry DEFINITIONS = new DefinitionRegistry();
    private static final int MAX_PENDING_CHUNKS = 4_096;
    private static final Map<MinecraftServer, RuntimeState> RUNTIMES = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, Set<PendingChunk>> PENDING_CHUNKS = new ConcurrentHashMap<>();

    public UltimaKingdoms() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, UltimaKingdomsConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, IntegrationConfig.SPEC, IntegrationConfig.FILE_NAME);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.ultimakingdoms.warfare.WarfareConfig.SPEC, "ultima-kingdoms-warfare-common.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.ultimakingdoms.evolution.EvolutionConfig.SPEC, "ultima-kingdoms-evolution-common.toml");
        MinecraftForge.EVENT_BUS.register(com.ultimakingdoms.evolution.EvolutionRuntime.class);
        MinecraftForge.EVENT_BUS.register(com.ultimakingdoms.evolution.EvolutionCommands.class);
        MinecraftForge.EVENT_BUS.register(com.ultimakingdoms.evolution.ProtectionCommands.class);
        MinecraftForge.EVENT_BUS.register(com.ultimakingdoms.evolution.RecruitTransferCommands.class);
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        Presentation.init(modBus);
        IntegrationBootstrap.init(modBus);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(com.ultimakingdoms.politics.PoliticalCommands.class);
        MinecraftForge.EVENT_BUS.register(com.ultimakingdoms.warfare.WarfareCommands.class);
        MinecraftForge.EVENT_BUS.register(com.ultimakingdoms.warfare.WarfareRuntime.class);
        MinecraftForge.EVENT_BUS.register(com.ultimakingdoms.warfare.MilitaryTargetPolicy.class);
    }

    @SubscribeEvent
    public void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(DEFINITIONS.reloadListener());
        event.addListener(POLITICS);
        event.addListener(com.ultimakingdoms.evolution.EvolutionDefinitions.INSTANCE);
        event.addListener(com.ultimakingdoms.evolution.drama.DramaRuntime.reloadListener());
        event.addListener(com.ultimakingdoms.civic.CivicDefinitions.INSTANCE);
        event.addListener(com.ultimakingdoms.factions.organization.OrganizationRuntime.reloadListener());
        event.addListener(IntegrationBootstrap.gateReloadListener());
    }

    @SubscribeEvent
    public void serverAboutToStart(ServerAboutToStartEvent event) {
        com.ultimakingdoms.evolution.drama.DramaRuntime.commitPending();
        com.ultimakingdoms.evolution.EvolutionDefinitions.INSTANCE.commitPending();
        DEFINITIONS.commitPending();
        POLITICS.commitPending();
        com.ultimakingdoms.civic.CivicDefinitions.INSTANCE.commitPending();
        com.ultimakingdoms.factions.organization.OrganizationRuntime.commitPending();
        PENDING_CHUNKS.computeIfAbsent(event.getServer(), ignored -> ConcurrentHashMap.newKeySet());
    }

    @SubscribeEvent
    public void serverStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        KingdomsServiceImpl service = new KingdomsServiceImpl(server, DEFINITIONS);
        ApiBootstrap.attach(server, service);
        Registration mca = () -> { };
        Registration integrations = () -> { };
        try {
            IntegrationBootstrap.commitPending(service);
            com.ultimakingdoms.knowledge.SettlementKnowledge.get(server).adopt(server, service);
            integrations = IntegrationBootstrap.attach(server, service);
            mca = McaCompat.attach(server, service);
            var politics = new com.ultimakingdoms.politics.GovernmentService(server, service, POLITICS);
            com.ultimakingdoms.api.politics.UltimaPoliticsApi.attach(server, politics);
            com.ultimakingdoms.warfare.CampaignService.attach(server);
            com.ultimakingdoms.compat.crime.JurisdictionPolicyBridge.attach(server);
            MinecraftForge.EVENT_BUS.register(politics);
            RUNTIMES.put(server, new RuntimeState(service, mca, integrations, politics));
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
            RuntimeState failedRuntime = RUNTIMES.remove(server);
            if (failedRuntime != null) MinecraftForge.EVENT_BUS.unregister(failedRuntime.politics());
            com.ultimakingdoms.api.politics.UltimaPoliticsApi.detach(server);
            com.ultimakingdoms.compat.crime.JurisdictionPolicyBridge.detach(server);
            com.ultimakingdoms.warfare.CampaignService.clear(server);
            try {
                mca.close();
            } finally {
                try {
                    integrations.close();
                } finally {
                    ApiBootstrap.detach(server, service);
                }
            }
            throw exception;
        }
    }

    @SubscribeEvent
    public void datapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        com.ultimakingdoms.evolution.drama.DramaRuntime.commitPending();
        com.ultimakingdoms.evolution.EvolutionDefinitions.INSTANCE.commitPending();
        MinecraftServer server = event.getPlayerList().getServer();
        DEFINITIONS.commitPending();
        POLITICS.commitPending();
        com.ultimakingdoms.civic.CivicDefinitions.INSTANCE.commitPending();
        com.ultimakingdoms.factions.organization.OrganizationRuntime.commitPending();
        RuntimeState runtime = RUNTIMES.get(server);
        if (runtime != null) {
            IntegrationBootstrap.commitPending(runtime.service());
            runtime.service().definitionsReloaded();
        }
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
        com.ultimakingdoms.evolution.drama.DramaRuntime.tick(event.getServer());
        RuntimeState runtime = RUNTIMES.get(event.getServer());
        if (runtime != null) runtime.service().tickDiscovery();
    }

    @SubscribeEvent
    public void settlementMerged(com.ultimakingdoms.api.event.SettlementMergedEvent event) {
        com.ultimakingdoms.knowledge.SettlementKnowledge.get(event.server())
                .merge(event.source().id(), event.target().id());
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
        com.ultimakingdoms.evolution.drama.DramaRuntime.clear(event.getServer());
        com.ultimakingdoms.compat.crime.JurisdictionPolicyBridge.detach(event.getServer());
        com.ultimakingdoms.warfare.CampaignService.clear(event.getServer());
        com.ultimakingdoms.warfare.WarfareRuntime.clear(event.getServer());
        PENDING_CHUNKS.remove(event.getServer());
        RuntimeState runtime = RUNTIMES.remove(event.getServer());
        if (runtime != null) {
            MinecraftForge.EVENT_BUS.unregister(runtime.politics());
            com.ultimakingdoms.api.politics.UltimaPoliticsApi.detach(event.getServer());
            try {
                runtime.mca().close();
            } finally {
                try {
                    runtime.integrations().close();
                } finally {
                    ApiBootstrap.detach(event.getServer(), runtime.service());
                }
            }
        }
        IntegrationBootstrap.clear();
        DEFINITIONS.clear();
        POLITICS.clear();
        com.ultimakingdoms.evolution.EvolutionDefinitions.INSTANCE.clear();
        com.ultimakingdoms.civic.CivicDefinitions.INSTANCE.clear();
        com.ultimakingdoms.factions.organization.OrganizationRuntime.clear();
    }

    private record RuntimeState(KingdomsServiceImpl service, Registration mca, Registration integrations, com.ultimakingdoms.politics.GovernmentService politics) {
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
