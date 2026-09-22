package dev.otectus.mcaquests.network;

import dev.otectus.mcaquests.api.ExternalMapPoint;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExternalMapPointsPacketTest {
    @Test void filteredSnapshotRoundTripsAndRejectsOversize(){
        var packet=new ExternalMapPointsS2CPacket("ultima_kingdoms",List.of(new ExternalMapPoint("site:1",Level.OVERWORLD,
                new BlockPos(4,70,-8),"Known trade post", ExternalMapPoint.Kind.SITE,false,false)));
        var buf=new FriendlyByteBuf(Unpooled.buffer());try{ExternalMapPointsS2CPacket.encode(packet,buf);assertEquals(packet,ExternalMapPointsS2CPacket.decode(buf));}finally{buf.release();}
        assertThrows(IllegalArgumentException.class,()->new ExternalMapPointsS2CPacket("bad owner",List.of()));
    }
}
