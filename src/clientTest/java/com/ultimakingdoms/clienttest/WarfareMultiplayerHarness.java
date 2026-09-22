package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.warfare.WarfareNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;

public final class WarfareMultiplayerHarness {
    private final Minecraft mc=Minecraft.getInstance();
    private final Path shared=Path.of(System.getProperty("ultima.clientTest.shared"));
    private final String role=System.getProperty("ultima.clientTest.role");
    private final long started=System.currentTimeMillis();
    private boolean connecting,finished;private int stage,ticks,sent;private UUID request;private WarfareNetwork.Snapshot reply;
    public WarfareMultiplayerHarness(){WarfareNetwork.receiver(r->{if(r.request().equals(request))reply=r;});}
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){if(event.phase!=TickEvent.Phase.END||finished)return;ticks++;
        try{
            if(System.currentTimeMillis()-started>240000)throw new AssertionError("R3 multiplayer timeout stage "+stage);
            if(!connecting&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen)){
                connecting=true;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(2);String addr=System.getProperty("ultima.clientTest.server");
                ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(addr),new ServerData("R3 validation",addr,false),false);
            }
            if(mc.player==null)return;
            if(stage==0&&Files.exists(shared.resolve("SETTLEMENT"))){request=UUID.randomUUID();WarfareNetwork.receiver(r->{if(r.request().equals(request))reply=r;});WarfareNetwork.request(request,UUID.fromString(Files.readString(shared.resolve("SETTLEMENT")).strip()));sent=ticks;stage=1;}
            else if(stage==1){
                if(role.equals("leader")&&reply!=null){if(!reply.title().equals("Multiplayer Harbor"))throw new AssertionError("Wrong authorized snapshot");Files.writeString(shared.resolve("leader-READ"),"private snapshot received");stage=2;}
                else if(role.equals("stranger")&&ticks-sent>80){if(reply!=null)throw new AssertionError("Undiscovered control snapshot leaked");Files.writeString(shared.resolve("stranger-READ"),"guessed UUID denied");stage=2;}
            }else if(stage==2&&Files.exists(shared.resolve(role+"-SEND"))){
                Object packet=Class.forName("com.talhanation.recruits.network.MessageChangeDiplomacyStatus").getConstructor().newInstance();
                for(var entry:Map.of("ownTeam","multi_a","otherTeam","multi_b").entrySet()){var field=packet.getClass().getDeclaredField(entry.getKey());field.setAccessible(true);field.set(packet,entry.getValue());}
                var status=packet.getClass().getDeclaredField("status");status.setAccessible(true);status.setByte(packet,(byte)2);
                ((net.minecraftforge.network.simple.SimpleChannel)Class.forName("com.talhanation.recruits.Main").getField("SIMPLE_CHANNEL").get(null)).sendToServer(packet);
                Files.writeString(shared.resolve(role+"-SENT"),"native packet sent");stage=3;
            }else if(stage==3&&Files.exists(shared.resolve("STOP"))){Files.writeString(shared.resolve(role+"-PASS.txt"),"PASS "+role+" real R3 snapshot privacy and native diplomacy packet exercise\n");finished=true;mc.stop();}
        }catch(Throwable failure){finished=true;failure.printStackTrace();try{Files.writeString(shared.resolve(role+"-FAIL.txt"),failure.toString());}catch(Exception ignored){}mc.stop();}
    }
}
