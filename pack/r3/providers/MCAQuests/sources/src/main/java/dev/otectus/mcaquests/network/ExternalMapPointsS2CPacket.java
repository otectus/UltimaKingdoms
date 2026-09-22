package dev.otectus.mcaquests.network;

import dev.otectus.mcaquests.api.ExternalMapPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/** Complete, per-viewer replacement for one integration's atlas points. */
public record ExternalMapPointsS2CPacket(String owner,List<ExternalMapPoint> points) {
    public ExternalMapPointsS2CPacket {if(owner==null||!owner.matches("[a-z0-9_.-]{1,64}")||points==null||points.size()>128)throw new IllegalArgumentException("invalid external map snapshot");points=List.copyOf(points);}
    public static void encode(ExternalMapPointsS2CPacket msg,FriendlyByteBuf buf){
        buf.writeUtf(msg.owner,64);buf.writeVarInt(msg.points.size());for(var point:msg.points){buf.writeUtf(point.key(),160);buf.writeResourceLocation(point.dimension().location());
            buf.writeBlockPos(point.position());buf.writeUtf(point.label(),128);buf.writeEnum(point.kind());buf.writeBoolean(point.approximate());buf.writeBoolean(point.lastKnown());}
    }
    public static ExternalMapPointsS2CPacket decode(FriendlyByteBuf buf){
        String owner=buf.readUtf(64);int size=buf.readVarInt();if(size<0||size>128)throw new IllegalArgumentException("external map snapshot too large");List<ExternalMapPoint> points=new ArrayList<>(size);
        for(int i=0;i<size;i++){String key=buf.readUtf(160);var dimension=ExternalMapPoint.dimension(buf.readResourceLocation().toString());BlockPos pos=buf.readBlockPos();String label=buf.readUtf(128);
            points.add(new ExternalMapPoint(key,dimension,pos,label,buf.readEnum(ExternalMapPoint.Kind.class),buf.readBoolean(),buf.readBoolean()));}return new ExternalMapPointsS2CPacket(owner,points);
    }
    public static void handle(ExternalMapPointsS2CPacket msg,Supplier<NetworkEvent.Context> ctx){var context=ctx.get();context.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            ()->()->acceptOnClient(msg)));context.setPacketHandled(true);}
    private static void acceptOnClient(ExternalMapPointsS2CPacket msg){
        try{Class.forName("dev.otectus.mcaquests.compat.mapatlases.client.AtlasRuntime").getMethod("acceptExternal",String.class,List.class)
                .invoke(null,msg.owner,msg.points);}catch(ReflectiveOperationException|LinkageError|RuntimeException ignored){/* Optional backend is absent or unavailable. */}
    }
}
