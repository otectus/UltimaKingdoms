package com.ultimakingdoms.warfare;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WarfareNetworkTest {
    @Test void boundedReadOnlySnapshotRoundTrips(){var snapshot=new WarfareNetwork.Snapshot(UUID.randomUUID(),UUID.randomUUID(),"Harbor",17,
            List.of("Sovereignty: recognized","Campaign: notice"),List.of(new WarfareNetwork.Command("Campaign","/ultima warfare campaigns")));
        var buffer=new FriendlyByteBuf(Unpooled.buffer());try{WarfareNetwork.encode(snapshot,buffer);assertEquals(snapshot,WarfareNetwork.decode(buffer));}finally{buffer.release();}}
    @Test void oversizedViewsAreRejected(){assertThrows(IllegalArgumentException.class,()->new WarfareNetwork.Snapshot(UUID.randomUUID(),UUID.randomUUID(),"x",0,
            java.util.Collections.nCopies(33,"line"),List.of()));assertThrows(IllegalArgumentException.class,()->new WarfareNetwork.Command("x","x".repeat(513)));}
}
