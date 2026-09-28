package com.ultimakingdoms.compat.recruits;

import com.ultimakingdoms.warfare.CampaignService;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.ModList;
import java.util.*;

public final class RecruitsEvents {
    private static boolean registered;
    private static final Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Fail-closed hooks stay silent by design; one line per failure kind tells operators why sieges are refused. */
    private static void failedClosed(String where, Throwable failure) {
        if (LOGGED.add(where + ":" + failure.getClass().getName()))
            com.mojang.logging.LogUtils.getLogger().warn("Native siege policy hook {} failed closed; sieges are refused until this is resolved: {}", where, failure.toString());
    }
    private RecruitsEvents() { }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register() {
        if (registered) return;
        var recruits = ModList.get().getModContainerById("recruits");
        if (recruits.isEmpty()) return;
        String version = recruits.get().getModInfo().getVersion().toString();
        if (!version.equals("1.15.2")) {
            // mods.toml admits any Recruits from 1.15.2 on, so an unaudited release launches; say once why
            // the integration is inert instead of leaving an operator to find it through refused commands.
            com.mojang.logging.LogUtils.getLogger().warn("Recruits {} is installed, but Ultima Kingdoms' Recruits "
                    + "integration is audited for 1.15.2 only and stays off: no Recruits mixin is applied, native "
                    + "claims read as unsupported, and warfare, mobilization and transfer commands refuse.", version);
            return;
        }
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
        } catch (ReflectiveOperationException | RuntimeException failure) { failedClosed("allowed", failure); return false; }
    }
    private static void siege(Event event) {
        try {
            var level = (ServerLevel)event.getClass().getMethod("getLevel").invoke(event);
            if (!level.getServer().isSameThread()) { event.setCanceled(true); return; }
            Object claim = event.getClass().getMethod("getClaim").invoke(event);
            if (!allowed(level, claim)) event.setCanceled(true);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failedClosed("siege", failure); event.setCanceled(true);
        }
    }
}
