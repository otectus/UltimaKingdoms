package com.ultimakingdoms.api.civic;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.*;
import java.util.function.BiFunction;

/** Optional social-provider boundary. Context is always resolved for the actual nearby speaker/viewer. */
public final class CivicNetworkApi {
    private static final Map<MinecraftServer,BiFunction<ServerPlayer,Entity,Optional<CivicContactContext>>> READERS=new WeakHashMap<>();
    @FunctionalInterface public interface Actions {
        CivicActionResult request(ServerPlayer player, Entity speaker, boolean introduction);
    }
    private static final Map<MinecraftServer,Actions> ACTIONS=new WeakHashMap<>();
    private CivicNetworkApi() { }
    public static CivicActionResult requestIntroduction(ServerPlayer player, Entity speaker) { return request(player,speaker,true); }
    public static CivicActionResult requestCommissions(ServerPlayer player, Entity speaker) { return request(player,speaker,false); }
    private static CivicActionResult request(ServerPlayer player,Entity speaker,boolean introduction) {
        if(!player.getServer().isSameThread())throw new IllegalStateException("Civic actions require server thread");
        var actions=ACTIONS.get(player.getServer());
        return actions==null?new CivicActionResult(false,"civic.institution_unavailable",Optional.empty()):actions.request(player,speaker,introduction);
    }
    public static void attachActions(MinecraftServer server,Actions actions) {
        if(!server.isSameThread())throw new IllegalStateException("Civic actions require server thread");
        if(ACTIONS.putIfAbsent(server,actions)!=null)throw new IllegalStateException("Civic actions already attached");
    }
    public static Optional<CivicContactContext> speakerContext(ServerPlayer player,Entity speaker) {
        if(!player.getServer().isSameThread()) throw new IllegalStateException("Civic context requires server thread");
        var reader=READERS.get(player.getServer());return reader==null?Optional.empty():reader.apply(player,speaker);
    }
    public static void attach(MinecraftServer server,BiFunction<ServerPlayer,Entity,Optional<CivicContactContext>> reader) {
        if(!server.isSameThread()) throw new IllegalStateException("Civic context requires server thread");
        if(READERS.putIfAbsent(server,reader)!=null) throw new IllegalStateException("Civic context already attached");
    }
    public static void detach(MinecraftServer server) { READERS.remove(server); ACTIONS.remove(server); }
}
