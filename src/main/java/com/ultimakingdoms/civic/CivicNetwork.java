package com.ultimakingdoms.civic;

import com.ultimakingdoms.api.factions.organization.OrganizationApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;
import java.util.function.Consumer;

/** Requests cannot name another player. All mutations and observations run on the authenticated server thread. */
public final class CivicNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder.named(new ResourceLocation("ultima_kingdoms", "civic"))
            .networkProtocolVersion(() -> "3").clientAcceptedVersions("3"::equals).serverAcceptedVersions("3"::equals).simpleChannel();
    public enum Action { READ, JOIN, LEAVE, INTRODUCE, COMMISSIONS, HOSPITALITY, WORKSHOP }
    public record Query(UUID request, int index, Action action, String organization) { }
    public record Reply(UUID request, CivicViews.View view, String outcome) { }
    private static final Map<UUID, Integer> LAST = new HashMap<>();
    private static final Map<UUID,Integer> LAST_MUTATION=new HashMap<>();
    private static Consumer<Reply> receiver = ignored -> { };
    private CivicNetwork() { }
    public static void init() {
        CHANNEL.messageBuilder(Query.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((q,b) -> { b.writeUUID(q.request()); b.writeVarInt(q.index()); b.writeEnum(q.action()); b.writeUtf(q.organization(),128); })
                .decoder(b -> new Query(b.readUUID(), b.readVarInt(), b.readEnum(Action.class), b.readUtf(128)))
                .consumerMainThread((q,ctx) -> {
                    var player = ctx.get().getSender(); if (player == null || q.index() < 0 || q.index() > 4096) return;
                    var throttle=q.action()==Action.READ?LAST:LAST_MUTATION;
                    int now = player.getServer().getTickCount(); var before = throttle.get(player.getUUID());
                    if (before != null && now - before < 4) return;
                    throttle.put(player.getUUID(), now);
                    try {
                        String outcome = "";
                        // Validate selected organization before mutation. No hidden UUID or permission writes.
                        var view = CivicViews.own(player, q.index());
                        if (q.action() != Action.READ) {
                            if (!view.organization().equals(q.organization()) || q.organization().isEmpty()) return;
                            var service = OrganizationApi.get(player.getServer());
                            var id = new ResourceLocation(q.organization());
                            if(q.action()==Action.JOIN||q.action()==Action.LEAVE) {
                                var result = q.action() == Action.JOIN ? service.join(player,id) : service.leave(player,id);
                                outcome = result.reason();
                            } else {
                                var civic=CivicRuntime.get(player.getServer());var contact=civic.nearbyContact(player,id);
                                var result=contact.isEmpty()?CivicService.Result.deny("civic.contact_unavailable"):
                                        q.action()==Action.INTRODUCE?civic.introduction(player,contact.get()):q.action()==Action.HOSPITALITY?civic.hospitality(player,contact.get()):q.action()==Action.WORKSHOP?civic.workshop(player,contact.get()):civic.commissions(player,contact.get());
                                outcome=result.reason();
                                result.settlement().flatMap(com.ultimakingdoms.api.UltimaKingdomsApi.get(player.getServer())::getSettlement)
                                        .ifPresent(s -> player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("civic.introduction_destination",s.displayName(),s.anchor().toShortString())));
                            }
                            view = CivicViews.own(player,q.index());
                        }
                        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),new Reply(q.request(),view,outcome));
                    } catch (IllegalArgumentException | IllegalStateException ignored) { }
                }).add();
        CHANNEL.messageBuilder(Reply.class,1,NetworkDirection.PLAY_TO_CLIENT)
                .encoder((r,b) -> { b.writeUUID(r.request()); var v=r.view(); b.writeUtf(v.organization(),128); b.writeUtf(v.nameKey(),256);
                    b.writeVarInt(v.index()); b.writeVarInt(v.total()); b.writeBoolean(v.active());
                    b.writeCollection(v.lines(),(out,s) -> out.writeUtf(net.minecraft.network.chat.Component.Serializer.toJson(s),2048)); b.writeUtf(r.outcome(),512); })
                .decoder(b -> { UUID id=b.readUUID(); String org=b.readUtf(128), name=b.readUtf(256); int index=b.readVarInt(), total=b.readVarInt();
                    boolean active=b.readBoolean(); int count=b.readVarInt(); if(count<0||count>32) throw new IllegalArgumentException("Oversized guild view");
                    List<net.minecraft.network.chat.Component> lines=new ArrayList<>(); for(int i=0;i<count;i++) lines.add(Objects.requireNonNull(net.minecraft.network.chat.Component.Serializer.fromJson(b.readUtf(2048))));
                    return new Reply(id,new CivicViews.View(org,name,index,total,active,lines),b.readUtf(512)); })
                .consumerMainThread((r,ctx) -> receiver.accept(r)).add();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(CivicNetwork.class);
    }
    public static void receiver(Consumer<Reply> value) { receiver=value; }
    public static void send(Query query) { CHANNEL.sendToServer(query); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) { LAST.remove(e.getEntity().getUUID()); LAST_MUTATION.remove(e.getEntity().getUUID()); }
    @SubscribeEvent public static void stop(ServerStoppedEvent e) { LAST.clear(); LAST_MUTATION.clear(); }
}
