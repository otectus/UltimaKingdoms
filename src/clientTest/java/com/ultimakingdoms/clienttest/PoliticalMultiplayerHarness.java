package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.politics.PoliticalNetwork;
import com.ultimakingdoms.politics.PoliticalSavedData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;

public final class PoliticalMultiplayerHarness {
    private final Minecraft mc=Minecraft.getInstance();
    private final Path shared=Path.of(System.getProperty("ultima.clientTest.shared"));
    private final String role=System.getProperty("ultima.clientTest.role");
    private final long started=System.currentTimeMillis();
    private int stage,ticks,last;private boolean connecting,finished;private UUID pending;
    private PoliticalNetwork.Reply reply;
    private PoliticalNetwork.Query pendingQuery; private long sentAt;
    public PoliticalMultiplayerHarness(){PoliticalNetwork.receiver(r->{if(r.page().requestId().equals(pending))reply=r;});}
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||finished)return;ticks++;
        try{
            if(System.currentTimeMillis()-started>240000)throw new AssertionError("Multiplayer timeout stage "+stage);
            if(!connecting&&(mc.screen instanceof TitleScreen || mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)){connecting=true;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(2);
                String address=System.getProperty("ultima.clientTest.server");ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(address),new ServerData("Political acceptance",address,false),false);}
            if(ticks % 200 == 0) System.out.println("POLITICAL_MULTIPLAYER role="+role+" stage="+stage+" player="+(mc.player!=null)+" reply="+(reply!=null));
            if(mc.player==null||ticks-last<10)return;
            if((stage==1||stage==2)&&reply==null&&pendingQuery!=null&&System.currentTimeMillis()-sentAt>1000){
                PoliticalNetwork.send(pendingQuery);sentAt=System.currentTimeMillis();
            }
            if(stage==0){
                if(!Files.exists(shared.resolve("READY"))||role.equals("stranger")&&!Files.exists(shared.resolve("private.json")))return;
                query(null);stage=1;last=ticks;
            }else if(stage==1&&reply!=null){
                Page page=reply.page();Request mutation;
                if(role.equals("leader")){
                    if(page.rows().size()!=1)throw new AssertionError("Leader did not receive private proposal");
                    Row row=page.rows().get(0);String hash=row.termsHash();
                    mutation=new Request(UUID.randomUUID(),page.revision(),Action.SIGN,"ultima_kingdoms:serenum","",row.id(),null,"","",hash,0,0,0);
                }else{
                    if(!page.rows().isEmpty())throw new AssertionError("Private proposal leaked to unrelated client");
                    Request stolen=PoliticalSavedData.JSON.fromJson(Files.readString(shared.resolve("private.json")),Request.class);
                    mutation=new Request(UUID.randomUUID(),page.revision(),Action.SIGN,stolen.kingdom(),"",stolen.target(),null,"","",stolen.termsHash(),0,0,0);
                }
                query(mutation);stage=2;last=ticks;
            }else if(stage==2&&reply!=null){
                Result result=reply.result();
                if(role.equals("leader")){
                    if(result==null||!result.success())throw new AssertionError("Authorized network signature failed: "+result);
                    Row row=reply.page().rows().get(0);String hash=row.termsHash();
                    Files.writeString(shared.resolve("private.json"),PoliticalSavedData.JSON.toJson(new Request(UUID.randomUUID(),reply.page().revision(),Action.SIGN,"ultima_kingdoms:serenum","",row.id(),null,"","",hash,0,0,0)));
                }else if(result==null||result.success()||!result.message().contains("ratify")||!reply.page().rows().isEmpty())throw new AssertionError("Unauthorized request not rejected privately: "+result);
                Files.writeString(shared.resolve(role+"-PASS.txt"),role.equals("leader")?"PASS real authorized client sees and signs private proposal\n":"PASS real unauthorized client receives no proposal and cannot sign even with stolen record/hash\n");
                stage=3;last=ticks;
            }else if(stage==3&&Files.exists(shared.resolve("stranger-PASS.txt"))){finished=true;mc.stop();}
        }catch(Throwable failure){finished=true;failure.printStackTrace();try{Files.writeString(shared.resolve(role+"-FAIL.txt"),failure.toString());}catch(Exception ignored){}mc.stop();}
    }
    private void query(Request mutation){PoliticalNetwork.receiver(r->{System.out.println("POLITICAL_MULTIPLAYER reply="+r.page().requestId()+" expected="+pending);if(r.page().requestId().equals(pending))reply=r;});reply=null;pending=UUID.randomUUID();pendingQuery=new PoliticalNetwork.Query(pending,"ultima_kingdoms:serenum","agreements",0,mutation);sentAt=System.currentTimeMillis();PoliticalNetwork.send(pendingQuery);}
}
