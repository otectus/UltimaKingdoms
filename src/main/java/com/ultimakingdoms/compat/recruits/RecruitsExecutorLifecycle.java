package com.ultimakingdoms.compat.recruits;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.InvocationTargetException;

/** Cleans up the exact audited Recruits delayed executor without linking it when absent. */
@Mod.EventBusSubscriber(modid = UltimaKingdomsApi.MOD_ID)
public final class RecruitsExecutorLifecycle {
    private static final String TARGET = "com.talhanation.recruits.util.DelayedExecutor";
    private static final String VERSION = "1.15.2";

    private RecruitsExecutorLifecycle() { }

    @SubscribeEvent
    public static void starting(ServerStartingEvent event) {
        invoke("ultimaKingdoms$startScheduler");
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        invoke("ultimaKingdoms$stopScheduler");
    }

    private static void invoke(String method) {
        if (!supported()) return;
        try {
            var callback = Class.forName(TARGET).getDeclaredMethod(method);
            callback.setAccessible(true);
            callback.invoke(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    ? invocation.getCause() : failure;
            com.mojang.logging.LogUtils.getLogger().error(
                    "Recruits delayed executor lifecycle hook {} failed", method, cause);
        }
    }

    private static boolean supported() {
        return ModList.get().getModContainerById("recruits")
                .map(container -> VERSION.equals(container.getModInfo().getVersion().toString()))
                .orElse(false);
    }
}
