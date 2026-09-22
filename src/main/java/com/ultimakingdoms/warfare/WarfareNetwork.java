package com.ultimakingdoms.warfare;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.worldcontext.WorldContextApi;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.mobilization.MobilizationEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.function.Consumer;

/** Authenticated war-room snapshots with direct links to server-reviewed GUI tasks. */
@Mod.EventBusSubscriber(modid=UltimaKingdomsApi.MOD_ID,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class WarfareNetwork {
    private static SimpleChannel CHANNEL;
    public record Request(UUID request,UUID settlement){ }
    public record Command(String label,String value){public Command{if(label==null||label.length()>64||value==null||value.length()>512)throw new IllegalArgumentException("invalid war-room command");}}
    public record Snapshot(UUID request,UUID settlement,String title,long revision,List<String> lines,List<Command> commands){
        public Snapshot{if(title==null||title.length()>128||revision<0||lines==null||lines.size()>32||commands==null||commands.size()>8)throw new IllegalArgumentException("invalid war-room snapshot");
            if(lines.stream().anyMatch(line->line==null||line.length()>512))throw new IllegalArgumentException("invalid war-room line");lines=List.copyOf(lines);commands=List.copyOf(commands);}}
    private static final Map<UUID,Integer> LAST=new HashMap<>();private static Consumer<Snapshot> receiver=ignored->{};private static boolean initialized;
    private WarfareNetwork(){ }
    @SubscribeEvent public static void setup(FMLCommonSetupEvent event){event.enqueueWork(WarfareNetwork::init);}
    public static synchronized void init(){if(initialized)return;
        CHANNEL=NetworkRegistry.ChannelBuilder.named(new ResourceLocation("ultima_kingdoms","war_room"))
                .networkProtocolVersion(()->"2").clientAcceptedVersions("2"::equals).serverAcceptedVersions("2"::equals).simpleChannel();
        initialized=true;
        CHANNEL.messageBuilder(Request.class,0,NetworkDirection.PLAY_TO_SERVER)
                .encoder((q,b)->{b.writeUUID(q.request());b.writeUUID(q.settlement());}).decoder(b->new Request(b.readUUID(),b.readUUID()))
                .consumerMainThread((q,ctx)->{var player=ctx.get().getSender();if(player==null)return;int now=player.getServer().getTickCount();Integer prior=LAST.get(player.getUUID());
                    if(prior!=null&&now-prior<10)return;LAST.put(player.getUUID(),now);try{Snapshot snapshot=build(player,q);CHANNEL.send(PacketDistributor.PLAYER.with(()->player),snapshot);}
                    catch(IllegalArgumentException|IllegalStateException ignored){}}).add();
        CHANNEL.messageBuilder(Snapshot.class,1,NetworkDirection.PLAY_TO_CLIENT).encoder(WarfareNetwork::encode).decoder(WarfareNetwork::decode)
                .consumerMainThread((reply,ctx)->receiver.accept(reply)).add();net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(WarfareNetworkCleanup.class);
    }
    private static Snapshot build(net.minecraft.server.level.ServerPlayer viewer,Request request){var server=viewer.getServer();var kingdoms=UltimaKingdomsApi.get(server);
        var settlement=kingdoms.getSettlement(request.settlement()).orElseThrow(()->new IllegalArgumentException("Settlement unavailable"));
        if(!SettlementKnowledge.get(server).visible(viewer,settlement.id()))throw new IllegalArgumentException("Settlement undiscovered");
        var campaigns=CampaignService.get(server);List<String> lines=new ArrayList<>(campaigns.page(viewer,settlement.id()));
        WorldContextApi.get(server).ifPresent(world->{var routes=world.routes(viewer,0,8);lines.add("Known institution routes: "+routes.size());
            routes.stream().limit(3).forEach(r->lines.add("Route "+r.id()+" | "+r.status()+" | checkpoint "+(r.nextCheckpoint()+1)+"/"+r.checkpoints().size()));});
        try{var mobilization=MobilizationEvents.get(server).status(viewer);lines.add("Active deployments: "+mobilization.size());mobilization.stream().limit(3).forEach(lines::add);}
        catch(IllegalArgumentException unavailable){lines.add("Mobilization provider unavailable; retained leases are not reported as restored.");}
        if(lines.size()>32)lines.subList(32,lines.size()).clear();lines.replaceAll(line->line.length()>512?line.substring(0,512):line);
        long revision=campaigns.revision();
        var context=new com.ultimakingdoms.interaction.ActionRegistry.Context(viewer,Map.of(),Map.of());
        lines.replaceAll(line->com.ultimakingdoms.interaction.PlayerWords.safe(line,context));
        List<Command> commands=List.of(new Command("Declare campaign","warfare.declare"),
                new Command("Propose control agreement","warfare.accord"),new Command("Deploy nearby recruits","warfare.muster_nearby"),
                new Command("Choose civilian work","warfare.contract"),new Command("Browse routes","world.routes"),new Command("Request resource access","world.resource"));
        return new Snapshot(request.request(),settlement.id(),settlement.displayName(),revision,lines,commands);
    }
    static void encode(Snapshot value,net.minecraft.network.FriendlyByteBuf out){out.writeUUID(value.request());out.writeUUID(value.settlement());out.writeUtf(value.title(),128);out.writeVarLong(value.revision());
        out.writeVarInt(value.lines().size());value.lines().forEach(line->out.writeUtf(line,512));out.writeVarInt(value.commands().size());value.commands().forEach(c->{out.writeUtf(c.label(),64);out.writeUtf(c.value(),512);});}
    static Snapshot decode(net.minecraft.network.FriendlyByteBuf in){UUID request=in.readUUID(),settlement=in.readUUID();String title=in.readUtf(128);long revision=in.readVarLong();
        int lineCount=in.readVarInt();if(lineCount<0||lineCount>32)throw new IllegalArgumentException("oversized war-room lines");List<String> lines=new ArrayList<>();for(int i=0;i<lineCount;i++)lines.add(in.readUtf(512));
        int commandCount=in.readVarInt();if(commandCount<0||commandCount>8)throw new IllegalArgumentException("oversized war-room commands");List<Command> commands=new ArrayList<>();for(int i=0;i<commandCount;i++)commands.add(new Command(in.readUtf(64),in.readUtf(512)));
        return new Snapshot(request,settlement,title,revision,lines,commands);}
    public static void receiver(Consumer<Snapshot> value){receiver=Objects.requireNonNull(value);}public static void request(UUID request,UUID settlement){CHANNEL.sendToServer(new Request(request,settlement));}
    static void forget(UUID player) { LAST.remove(player); }
    static void clear() { LAST.clear(); }
}
