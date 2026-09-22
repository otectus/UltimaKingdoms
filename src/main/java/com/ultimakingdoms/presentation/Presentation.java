package com.ultimakingdoms.presentation;

import com.ultimakingdoms.client.ClientBootstrap;
import com.ultimakingdoms.command.CommandArguments;
import com.ultimakingdoms.command.UltimaCommands;
import com.ultimakingdoms.item.ModItems;
import com.ultimakingdoms.network.NetworkHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public final class Presentation {
    private Presentation() {
    }

    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.ultimakingdoms.civic.CivicConfig.SPEC,
                "ultima-kingdoms-civic-common.toml");
        CommandArguments.register(modBus);
        ModItems.register(modBus);
        NetworkHandler.init();
        com.ultimakingdoms.civic.CivicNetwork.init();
        MinecraftForge.EVENT_BUS.register(com.ultimakingdoms.civic.CivicCommands.class);
        MinecraftForge.EVENT_BUS.register(UltimaCommands.class);
        MinecraftForge.EVENT_BUS.register(ServerPresentationEvents.class);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, PresentationConfig.SPEC,
                "ultima-kingdoms-client.toml");
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::init);
    }
}
