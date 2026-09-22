package com.ultimakingdoms.interaction;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

/** Bounded, connection-scoped permission to reveal one recipient-owned native group to one sender. */
@Mod.EventBusSubscriber(modid="ultima_kingdoms")
public final class RecruitTransferDestinations {
    public record Destination(UUID recipient,UUID sender,UUID group,String name,long revision){}
    private record Key(UUID recipient,UUID sender,UUID group){}
    private static final int LIMIT=256;
    private static final Map<MinecraftServer,LinkedHashMap<Key,Destination>> SHARES=new WeakHashMap<>();
    private static final Map<MinecraftServer,Long> REVISIONS=new WeakHashMap<>();

    public static String share(ServerPlayer recipient,UUID sender,UUID group,String name){
        server(recipient);Objects.requireNonNull(sender);Objects.requireNonNull(group);name=plain(name);
        if(recipient.getUUID().equals(sender))throw new IllegalArgumentException("Choose another online player as the allowed sender.");
        if(recipient.getServer().getPlayerList().getPlayer(sender)==null)throw new IllegalArgumentException("The allowed sender must be online.");
        var values=SHARES.computeIfAbsent(recipient.getServer(),ignored->new LinkedHashMap<>());var key=new Key(recipient.getUUID(),sender,group);
        if(!values.containsKey(key)&&values.size()>=LIMIT)throw new IllegalArgumentException("Shared transfer destination limit reached; revoke an old share first.");
        long revision=REVISIONS.merge(recipient.getServer(),1L,Math::addExact);values.put(key,new Destination(recipient.getUUID(),sender,group,name,revision));
        return "Shared destination “"+name+"” with the selected sender for this connection. It is revoked when either player disconnects or the server stops.";
    }
    public static String revoke(ServerPlayer recipient,UUID sender,UUID group){
        server(recipient);var values=SHARES.get(recipient.getServer());if(values==null||values.remove(new Key(recipient.getUUID(),sender,group))==null)throw new IllegalArgumentException("That shared destination is no longer active.");
        REVISIONS.merge(recipient.getServer(),1L,Math::addExact);return "Recruit transfer destination revoked. Existing durable proposals keep their frozen destination and still require bilateral consent.";
    }
    public static List<Destination> forSender(ServerPlayer sender,UUID recipient){server(sender);return SHARES.getOrDefault(sender.getServer(),new LinkedHashMap<>()).values().stream()
            .filter(v->v.sender().equals(sender.getUUID())&&v.recipient().equals(recipient)).toList();}
    public static List<Destination> owned(ServerPlayer recipient){server(recipient);return SHARES.getOrDefault(recipient.getServer(),new LinkedHashMap<>()).values().stream()
            .filter(v->v.recipient().equals(recipient.getUUID())).toList();}
    public static long revision(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Transfer destinations require server thread");return REVISIONS.getOrDefault(server,0L);}
    private static void server(ServerPlayer player){if(player==null||player.getServer()==null||!player.getServer().isSameThread()||player.hasDisconnected())throw new IllegalArgumentException("Connected server player required.");}
    private static String plain(String value){if(value==null||value.isBlank()||value.length()>128||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid native group name.");return value;}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(!(event.getEntity() instanceof ServerPlayer player))return;var values=SHARES.get(player.getServer());if(values!=null&&values.entrySet().removeIf(e->e.getKey().recipient().equals(player.getUUID())||e.getKey().sender().equals(player.getUUID())))REVISIONS.merge(player.getServer(),1L,Math::addExact);}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){SHARES.remove(event.getServer());REVISIONS.remove(event.getServer());}
    private RecruitTransferDestinations(){}
}
