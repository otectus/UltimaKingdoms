package com.ultimakingdoms.compat.townstead;

import com.mojang.logging.LogUtils;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.townstead.UltimaTownsteadApi;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import com.ultimakingdoms.compat.townstead.network.TownsteadCivicNetwork;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TownsteadRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TownsteadRuntime() {
    }

    public static void registerGlobalHooks() {
        TownsteadIntegrationConfig.register();
        TownsteadCivicNetwork.init();
    }

    public static Registration attach(MinecraftServer server, KingdomsService kingdoms) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(kingdoms, "kingdoms");
        boolean installed = ModList.get().isLoaded("townstead") && TownsteadIntegrationConfig.ENABLED.get();
        TownsteadBinding binding = TownsteadBinding.resolve(installed,
                installed && TownsteadIntegrationConfig.ALLOW_INTERNAL_FALLBACK.get());
        TownsteadServiceImpl service = new TownsteadServiceImpl(server, kingdoms, binding);
        UltimaTownsteadApi.attach(server, service);
        TownsteadBuildingReconciler reconciler = new TownsteadBuildingReconciler(server, kingdoms, service);
        TownsteadPoliticalReactions reactions = new TownsteadPoliticalReactions(server, kingdoms, service);
        if (installed) {
            MinecraftForge.EVENT_BUS.register(reconciler);
            MinecraftForge.EVENT_BUS.register(reactions);
        }
        String version = ModList.get().getModContainerById("townstead")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("absent");
        LOGGER.info("[Ultima Kingdoms] Townstead {} capabilities: {}", version, service.capabilities());
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (!open.compareAndSet(true, false)) return;
            if (installed) {
                MinecraftForge.EVENT_BUS.unregister(reactions);
                MinecraftForge.EVENT_BUS.unregister(reconciler);
            }
            reconciler.clear();
            TownsteadCivicNetwork.clearServerState();
            UltimaTownsteadApi.detach(server, service);
        };
    }
}
