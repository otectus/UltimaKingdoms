package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.client.VillageLedgerScreen;
import com.ultimakingdoms.client.politics.KingdomScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;

/** Actual integrated-server packets and clicks at normal and compact GUI scales. */
public final class PoliticalClientHarness {
    private final Minecraft mc=Minecraft.getInstance();
    private final Path output=Path.of(System.getProperty("ultima.clientTest.output"));
    private final long started=System.currentTimeMillis();
    private int stage,ticks,entered,wizardStep; private boolean creating,finished;
    private volatile String settlement;
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||finished)return;
        ticks++;
        try {
            if(System.currentTimeMillis()-started>240000)throw new AssertionError("Politics client timeout stage "+stage);
            if(!creating&&mc.screen instanceof TitleScreen){
                creating=true;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(3);mc.options.guiScale().set(2);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.createWorldOpenFlows().createFreshLevel("politics-validation-"+started,new LevelSettings("Political UI",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                        new WorldOptions(445566,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
            }
            if(ticks-entered<30)return;
            switch(stage){
                case 0->{
                    if(mc.player==null||mc.getSingleplayerServer()==null)return;
                    mc.getSingleplayerServer().execute(()->{try{
                        var server=mc.getSingleplayerServer();var player=server.getPlayerList().getPlayers().get(0);var service=UltimaKingdomsApi.get(server);
                        BlockPos pos=player.blockPosition();var village=service.registerCandidate(server.overworld(),SettlementCandidate.external(server.overworld().dimension(),pos,32,SettlementBounds.around(pos,32),new ResourceLocation("ultima_client_test:politics"),"seat",new ResourceLocation("ultima_kingdoms:serenum"),Map.of(),"Bellmeadow"));
                        settlement=village.id().toString();com.ultimakingdoms.knowledge.SettlementKnowledge.get(server).discover(player.getUUID(),village.id());
                    }catch(Throwable failure){fail(failure);}});next();
                }
                case 1->{if(settlement==null||mc.screen!=null)return;mc.setScreen(new KingdomScreen(new VillageLedgerScreen(null),"ultima_kingdoms:serenum",settlement));next();}
                case 2->{if(!ready())return;check(page().rows().stream().anyMatch(r->r.title().equals("UNORGANIZED")),"Unorganized government shown");click("Actions");next();}
                case 3->{
                    if(!actionReady())return;var reply=actionReply();
                    if(reply.mode().equals("tasks")){if(wizardStep==0){searchActions("Establish a government");wizardStep++;}else click("Establish a government");}
                    else if(reply.mode().equals("choice")){String answer=switch(wizardStep++){case 1->"Serenum";case 2->"Bellmeadow";case 3->"Serenum Civic Crown";default->mc.player.getName().getString();};click(answer);}
                    else if(reply.mode().equals("review"))click("Apply reviewed action");
                    else if(reply.mode().equals("result")){check(!reply.detail().contains("Could not complete"),"Reviewed government established");mc.screen.onClose();click("Refresh");next();}
                    else throw new AssertionError(reply.detail());entered=ticks;
                }
                case 4->{if(!ready())return;check(page().rows().stream().anyMatch(r->r.title().equals("ACTIVE")),"Real packet constituted a government");shot("politics-overview.png");click("Council");next();}
                case 5->{if(!ready())return;check(page().rows().stream().anyMatch(r->r.id().equals("ultima_kingdoms:leader")),"Persistent leadership visible");shot("politics-council.png");click("Actions");next();}
                case 6->{if(!actionReady())return;searchActions("Appoint an official");next();}
                case 7->{if(!actionReady())return;if(actionReply().mode().equals("tasks")){click("Appoint an official");entered=ticks;return;}check(actionReply().mode().equals("choice"),"Appointment uses named selection");mc.getWindow().setGuiScale(mc.getWindow().getHeight()/240.0);mc.screen.resize(mc,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());check(mc.screen.height>=240&&mc.screen.height<=241,"Compact viewport reached");for(var child:mc.screen.children())if(child instanceof Button b)check(b.getY()+b.getHeight()<=mc.screen.height,"Form button stays on screen");shot("politics-form-compact.png");click("Close");next();}
                case 8->{click("Agreements");next();}
                case 9->{if(!ready())return;check(page().rows().isEmpty(),"No fabricated agreements");click("Petitions");next();}
                case 10->{if(!ready())return;shot("politics-petitions-compact.png");mc.screen.onClose();check(mc.screen instanceof VillageLedgerScreen,"Returns to the existing village ledger");
                    Files.createDirectories(output);Files.writeString(output.resolve("POLITICS_PASS.txt"),"PASS actual integrated-server packets, authorized capital founding, council view, action clicks, compact form, tabs and ledger return\n");finished=true;mc.stop();}
            }
        }catch(Throwable failure){fail(failure);}
    }
    private boolean actionReady(){return mc.screen instanceof com.ultimakingdoms.client.KingdomActionsScreen&&actionField("reply")!=null&&actionField("pending")==null;}
    private com.ultimakingdoms.interaction.InteractionNetwork.Reply actionReply(){return (com.ultimakingdoms.interaction.InteractionNetwork.Reply)actionField("reply");}
    private Object actionField(String name){try{var f=com.ultimakingdoms.client.KingdomActionsScreen.class.getDeclaredField(name);f.setAccessible(true);return f.get(mc.screen);}catch(Exception e){throw new RuntimeException(e);}}
    private void searchActions(String value){((net.minecraft.client.gui.components.EditBox)actionField("input")).setValue(value);click("Search");}
    private void next(){stage++;entered=ticks;}
    private boolean ready(){return mc.screen instanceof KingdomScreen&&field("page")!=null&&field("pending")==null;}
    private Page page(){return (Page)field("page");}
    private Object field(String name){try{var f=KingdomScreen.class.getDeclaredField(name);f.setAccessible(true);return f.get(mc.screen);}catch(Exception e){throw new RuntimeException(e);}}
    private void click(String name){Button b=mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(v->v.getMessage().getString().equals(name)).findFirst().orElseThrow(()->new AssertionError("Missing button "+name));check(b.active,"Enabled "+name);check(mc.screen.mouseClicked(b.getX()+b.getWidth()/2.0,b.getY()+b.getHeight()/2.0,0),"Clicked "+name);}
    private void shot(String file){Screenshot.grab(output.toFile(),file,mc.getMainRenderTarget(),c->System.out.println(c.getString()));}
    private void check(boolean good,String message){if(!good)throw new AssertionError(message);System.out.println("POLITICS_CLIENT PASS "+message);}
    private void fail(Throwable failure){finished=true;failure.printStackTrace();try{Files.createDirectories(output);Files.writeString(output.resolve("FAIL.txt"),"politics stage="+stage+" "+failure);}catch(Exception ignored){}mc.execute(mc::stop);}
}
