package com.ultimakingdoms.compat.recruits;

import com.ultimakingdoms.warfare.CampaignService;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.ModList;
import java.util.*;

public final class RecruitsEvents {
    private static boolean registered;
    private RecruitsEvents() { }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register() {
        if (registered || !ModList.get().getModContainerById("recruits").map(m -> m.getModInfo().getVersion().toString().equals("1.15.2")).orElse(false)) return;
        try {
            for (String event : new String[]{"Start", "Tick"}) {
                Class type = Class.forName("com.talhanation.recruits.SiegeEvent$" + event);
                MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, type, (java.util.function.Consumer<Event>)RecruitsEvents::siege);
            }
            registered = true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            com.mojang.logging.LogUtils.getLogger().error("Native siege policy hook unavailable", failure);
        }
    }
    public static boolean available() { return registered; }
    public static boolean allowed(ServerLevel level, Object claim) {
        try {
            UUID id = (UUID)claim.getClass().getMethod("getUUID").invoke(claim);
            var attackers = new ArrayList<String>();
            for (Object faction : (List<?>)claim.getClass().getField("attackingParties").get(claim))
                attackers.add((String)faction.getClass().getMethod("getStringID").invoke(faction));
            return CampaignService.get(level.getServer()).siegeAllowed(id, attackers);
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
    private static void siege(Event event) {
        try {
            var level = (ServerLevel)event.getClass().getMethod("getLevel").invoke(event);
            if (!level.getServer().isSameThread()) { event.setCanceled(true); return; }
            Object claim = event.getClass().getMethod("getClaim").invoke(event);
            if (!allowed(level, claim)) event.setCanceled(true);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            event.setCanceled(true);
        }
    }
}
