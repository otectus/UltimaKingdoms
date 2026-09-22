package com.ultimakingdoms.clienttest;

import com.mojang.blaze3d.platform.InputConstants;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.client.*;
import com.ultimakingdoms.interaction.InteractionNetwork;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Packaged-client acceptance for the authenticated named-task wizard. */
public final class InteractionClientHarness {
    private static final List<String> SCREENSHOTS=List.of("01-create-review.png","02-created-read.png","03-rename-result.png","04-compact-tasks.png","05-war-room-wizard.png");
    private final Minecraft mc=Minecraft.getInstance();
    private final Path output=Path.of(System.getProperty("ultima.clientTest.output"));
    private final long started=System.currentTimeMillis();
    private int stage,ticks,entered;private boolean creating,finished;private volatile UUID settlement;private volatile String serverName="";

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||finished)return;ticks++;
        try{
            if(mc.screen instanceof ChatScreen)throw new AssertionError("Named task workflow opened ChatScreen at stage "+stage);
            if(System.currentTimeMillis()-started>240_000L)throw new AssertionError("Interaction client timeout at stage "+stage+" screen="+mc.screen);
            if(!creating&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen))createWorld();
            if(!creating||ticks-entered<20)return;
            switch(stage){
                case 0->{if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null)return;mc.setScreen(null);InputConstants.Key key=InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K);KeyMapping.click(key);next();}
                case 1->{if(!ready("tasks"))return;check(mc.screen instanceof KingdomActionsScreen,"K key mapping opened Kingdom Actions");check(noInternalInputs(),"Initial catalogue has no editable UUID, revision, or fingerprint field");search("Register a settlement");next();}
                case 2->{if(!ready("tasks"))return;click("Register a settlement here");next();}
                case 3->{if(!ready("text"))return;check(reply().title().contains("Radius"),"Create wizard begins with bounded radius");enter("32");next();}
                case 4->{if(!ready("text"))return;check(reply().title().toLowerCase(Locale.ROOT).contains("name"),"Create wizard requests a readable name");enter("Wizard Reach");next();}
                case 5->{if(!ready("review"))return;check(!reply().detail().toLowerCase(Locale.ROOT).matches(".*(uuid|fingerprint|revision [0-9]).*"),"Create review contains only player-facing terms");shot("01-create-review.png");click("Apply reviewed action");next();}
                case 6->{if(!ready("result"))return;check(reply().detail().contains("Registered Wizard Reach"),"Reviewed create action completed once");seedDiscovery();click("All tasks");next();}
                case 7->{if(settlement==null||!ready("tasks"))return;search("Read a known settlement");next();}
                case 8->{if(!ready("tasks"))return;click("Read a known settlement");next();}
                case 9->{if(!ready("choice"))return;check(reply().options().stream().anyMatch(o->o.label().equals("Wizard Reach")),"Settlement picker uses the created name");click("Wizard Reach");next();}
                case 10->{if(!ready("review"))return;click("Apply reviewed action");next();}
                case 11->{if(!ready("result"))return;check(reply().detail().contains("Wizard Reach")&&reply().detail().contains("Location"),"Read task returns usable settlement details");shot("02-created-read.png");click("All tasks");next();}
                case 12->{if(!ready("tasks"))return;search("Rename a settlement");next();}
                case 13->{if(!ready("tasks"))return;click("Rename a settlement");next();}
                case 14->{if(!ready("choice"))return;click("Wizard Reach");next();}
                case 15->{if(!ready("text"))return;enter("Wizard Harbor");next();}
                case 16->{if(!ready("review"))return;click("Apply reviewed action");next();}
                case 17->{if(!ready("result"))return;check(reply().detail().contains("Wizard Harbor"),"Rename result uses the new readable name");verifyName();shot("03-rename-result.png");click("All tasks");next();}
                case 18->{if(!serverName.equals("Wizard Harbor")||!ready("tasks"))return;mc.getWindow().setGuiScale(mc.getWindow().getHeight()/240.0D);((Screen)mc.screen).resize(mc,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());next();}
                case 19->{if(!ready("tasks"))return;Screen screen=(Screen)mc.screen;check(screen.height>=240&&screen.height<=241,"Compact wizard is 240 GUI pixels high");for(var child:screen.children())if(child instanceof AbstractWidget widget&&widget.visible)check(widget.getX()>=0&&widget.getY()>=0&&widget.getX()+widget.getWidth()<=screen.width&&widget.getY()+widget.getHeight()<=screen.height,"Compact widget stays in viewport: "+widget.getMessage().getString());
                    search("evolution");next();}
                case 20->{if(!ready("tasks"))return;check(reply().options().stream().allMatch(o->(o.label()+" "+o.detail()).toLowerCase(Locale.ROOT).contains("evolution")),"Keyboard search filters named tasks");shot("04-compact-tasks.png");search("");next();}
                case 21->{if(!ready("tasks"))return;Button nextPage=button("Next page");check(nextPage.active,"Compact catalogue exposes paging");click(nextPage);check((Integer)field(KingdomActionsScreen.class,mc.screen,"localPage")==1,"Compact next-page mouse click advances local task rows");mc.options.guiScale().set(2);mc.resizeDisplay();mc.setScreen(new WarRoomScreen(null,settlement,"Wizard Harbor"));next();}
                case 22->{if(!(mc.screen instanceof WarRoomScreen)||field(WarRoomScreen.class,mc.screen,"snapshot")==null)return;click("Declare campaign");next();}
                case 23->{if(!ready("choice"))return;check(!(mc.screen instanceof ChatScreen),"War-room Campaign opens the named task wizard without chat");check(reply().title().equals("Campaign objective"),"Campaign handoff begins with named objective choices");click("Defend");next();}
                case 24->{if(!ready("choice"))return;check(reply().title().equals("Target settlement"),"Campaign wizard requests a named target settlement");click("Wizard Harbor");next();}
                case 25->{if(!ready("text"))return;enter("Protect the settlement while preserving civic identity");next();}
                case 26->{if(!ready("review"))return;shot("05-war-room-wizard.png");click("Apply reviewed action");next();}
                case 27->{if(!ready("result"))return;String detail=reply().detail().toLowerCase(Locale.ROOT);check(detail.contains("could not complete")&&(detail.contains("recruits")||detail.contains("provider")||detail.contains("faction leader")),"Missing native military authority is an explicit applicable blocked result");finish();}
            }
        }catch(Throwable failure){fail(failure);}
    }

    private void createWorld(){creating=true;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(3);mc.options.guiScale().set(2);mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.resizeDisplay();
        mc.createWorldOpenFlows().createFreshLevel("interaction-acceptance-"+started,new LevelSettings("Interaction acceptance",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                new WorldOptions(9142026L,false,false),registry->registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());log("Created isolated flat integrated world");}
    private void seedDiscovery(){mc.getSingleplayerServer().execute(()->{try{var player=mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);var found=UltimaKingdomsApi.get(mc.getSingleplayerServer()).getSettlementAt(player.serverLevel(),player.blockPosition()).orElseThrow();SettlementKnowledge.get(mc.getSingleplayerServer()).discover(player.getUUID(),found.id());settlement=found.id();serverName=found.displayName();}catch(Throwable failure){fail(failure);}});}
    private void verifyName(){mc.getSingleplayerServer().execute(()->{try{serverName=UltimaKingdomsApi.get(mc.getSingleplayerServer()).getSettlement(settlement).orElseThrow().displayName();check(serverName.equals("Wizard Harbor"),"Integrated server retained renamed settlement");}catch(Throwable failure){fail(failure);}});}
    private boolean ready(String mode){return mc.screen instanceof KingdomActionsScreen&&field(KingdomActionsScreen.class,mc.screen,"pending")==null&&reply()!=null&&reply().mode().equals(mode);}
    private InteractionNetwork.Reply reply(){return (InteractionNetwork.Reply)field(KingdomActionsScreen.class,mc.screen,"reply");}
    private boolean noInternalInputs(){return ((Screen)mc.screen).children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).noneMatch(e->e.getMessage().getString().toLowerCase(Locale.ROOT).matches(".*(uuid|revision|fingerprint).*"));}
    private void search(String value){EditBox input=(EditBox)field(KingdomActionsScreen.class,mc.screen,"input");input.setValue(value);((Screen)mc.screen).setFocused(input);check(((Screen)mc.screen).keyPressed(GLFW.GLFW_KEY_ENTER,0,0),"Keyboard Enter submits task search");}
    private void enter(String value){EditBox input=(EditBox)field(KingdomActionsScreen.class,mc.screen,"input");input.setValue(value);((Screen)mc.screen).setFocused(input);check(((Screen)mc.screen).keyPressed(GLFW.GLFW_KEY_ENTER,0,0),"Keyboard Enter advances text field");}
    private Button button(String label){Screen screen=(Screen)mc.screen;return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.getMessage().getString().equals(label)).findFirst().orElseThrow(()->new AssertionError("Missing button "+label+" among "+screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).map(w->w.getMessage().getString()).toList()));}
    private void click(String label){click(button(label));}
    private void click(Button button){check(button.active,"Button enabled: "+button.getMessage().getString());Screen screen=(Screen)mc.screen;check(screen.mouseClicked(button.getX()+button.getWidth()/2.0,button.getY()+button.getHeight()/2.0,0),"Mouse click handled: "+button.getMessage().getString());}
    private Object field(Class<?> type,Object instance,String name){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(instance);}catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}}
    private void shot(String name){Screenshot.grab(output.toFile(),name,mc.getMainRenderTarget(),message->log("Screenshot "+name+": "+message.getString()));}
    private void next(){stage++;entered=ticks;log("stage="+stage);}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);log("PASS "+message);}
    private void log(String message){System.out.println("INTERACTION_CLIENT "+message);}
    private void finish()throws Exception{for(String name:SCREENSHOTS){Path image=output.resolve("screenshots").resolve(name);check(Files.isRegularFile(image)&&Files.size(image)>0,"Screenshot written: "+name);}Files.writeString(output.resolve("INTERACTION_PASS.txt"),"PASS packaged interaction client: K key, named search, create/read/rename review and apply, no ChatScreen, compact paging, screenshots, and War Room campaign handoff to the wizard.\n");finished=true;mc.stop();}
    private void fail(Throwable failure){if(finished)return;finished=true;failure.printStackTrace();try{Files.createDirectories(output);Files.writeString(output.resolve("INTERACTION_FAIL.txt"),"stage="+stage+"\n"+failure+"\n");}catch(Exception writing){writing.printStackTrace();}mc.execute(mc::stop);}
}
