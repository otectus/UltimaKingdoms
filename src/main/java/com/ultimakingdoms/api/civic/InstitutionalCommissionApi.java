package com.ultimakingdoms.api.civic;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.*;

/** Provider calls only; neither a payment API nor authority supplied by a client. */
public final class InstitutionalCommissionApi {
    public interface Handler {
        String validate(ServerPlayer player, Entity giver, ResourceLocation quest, String binding, boolean completing);
        boolean accepted(ServerPlayer player, Entity giver, ResourceLocation quest, String binding, UUID instance);
        boolean cancelled(ServerPlayer player, ResourceLocation quest, String binding, UUID instance);
    }
    private static final Map<MinecraftServer,Handler> HANDLERS=new WeakHashMap<>();
    private InstitutionalCommissionApi() { }
    public static String validate(ServerPlayer player,Entity giver,ResourceLocation quest,String binding,boolean completing) {
        var handler=handler(player);
        return handler==null?"civic.institution_unavailable":handler.validate(player,giver,quest,binding,completing);
    }
    public static boolean accepted(ServerPlayer player,Entity giver,ResourceLocation quest,String binding,UUID instance) {
        var handler=handler(player);return handler!=null&&handler.accepted(player,giver,quest,binding,instance);
    }
    public static boolean cancelled(ServerPlayer player,ResourceLocation quest,String binding,UUID instance) {
        var handler=handler(player);return handler!=null&&handler.cancelled(player,quest,binding,instance);
    }
    private static Handler handler(ServerPlayer player) {
        if(player==null||player.getServer()==null||!player.getServer().isSameThread())throw new IllegalStateException("Institutional API requires server thread");
        return HANDLERS.get(player.getServer());
    }
    public static void attach(MinecraftServer server,Handler handler) {
        if(!server.isSameThread()||HANDLERS.putIfAbsent(server,Objects.requireNonNull(handler))!=null)
            throw new IllegalStateException("Institutional handler already attached or wrong thread");
    }
    public static void detach(MinecraftServer server) { HANDLERS.remove(server); }
}
