package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.politics.PoliticalNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;

public final class EvolutionMultiplayerHarness {
    private final Minecraft mc=Minecraft.getInstance();
    private final Path shared=Path.of(System.getProperty("ultima.clientTest.shared"));
    private final String role=System.getProperty("ultima.clientTest.role");
    private final long start=System.currentTimeMillis();
    private boolean connecting,finished;private int stage;private UUID request;private PoliticalNetwork.Reply reply;private long sent;
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){if(event.phase!=TickEvent.Phase.END||finished)return;
        try{
            if(System.currentTimeMillis()-start>240000)throw new AssertionError("R4 multiplayer timeout "+stage);
            if(!connecting&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen)){
                connecting=true;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(2);String address=System.getProperty("ultima.clientTest.server");
                ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(address),new ServerData("R4 acceptance",address,false),false);
            }
            if(mc.player==null)return;
            if(stage==0&&Files.exists(shared.resolve("PETITION"))){
                request=UUID.randomUUID();PoliticalNetwork.receiver(r->{if(r.page().requestId().equals(request))reply=r;});
                query();stage=1;
            }else if(stage==1){
                if(reply==null){if(System.currentTimeMillis()-sent>1000)query();return;}
                String petition=Files.readString(shared.resolve("PETITION")).strip();
                boolean found=reply.page().rows().stream().anyMatch(r->r.id().equals(petition));
                if(found!=role.equals("leader"))throw new AssertionError("Private history network audience mismatch: "+role);
                Files.writeString(shared.resolve(role+"-READ"),"history audience verified");stage=2;
            }else if(stage==2&&Files.exists(shared.resolve("RACE"))){
                String id=Files.readString(shared.resolve("SCENARIO")).strip();
                mc.player.connection.sendCommand("ultima-evolution resolve "+(role.equals("leader")?"decline":"succeed")+" "+id+" 2");
                Files.writeString(shared.resolve(role+"-SENT"),"opposing outcome sent");stage=3;
            }else if(stage==3&&Files.exists(shared.resolve("STOP"))){
                Files.writeString(shared.resolve(role+"-PASS.txt"),"PASS "+role+" real R4 private history packet and competing outcome command\n");finished=true;mc.stop();
            }
        }catch(Throwable failure){finished=true;failure.printStackTrace();try{Files.writeString(shared.resolve(role+"-FAIL.txt"),failure.toString());}catch(Exception ignored){}mc.stop();}
    }
    private void query(){PoliticalNetwork.send(new PoliticalNetwork.Query(request,"ultima_kingdoms:serenum","history",0,null));sent=System.currentTimeMillis();}
}
