package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.factions.*;
import com.ultimakingdoms.client.townstead.TownsteadBlueprintHeader;
import com.ultimakingdoms.compat.townstead.network.TownsteadCivicHeaderPacket.Header;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Packaged MCA/Townstead screen and real network acceptance, never shipped. */
public final class BlueprintAcceptanceHarness {
    private final Minecraft mc = Minecraft.getInstance();
    private final Path output = Path.of(System.getProperty("ultima.clientTest.output"));
    private final long started = System.currentTimeMillis();
    private int stage, ticks, entered;
    private boolean creating, done;
    private volatile Object village;
    private volatile UUID settlement;
    private Screen blueprint;
    private final ConversationEntryAcceptance conversations = new ConversationEntryAcceptance();
    private final ResourceLocation initial = new ResourceLocation("ultima_kingdoms:serenum");
    private final ResourceLocation changed = new ResourceLocation("ultima_kingdoms:lunari");
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        ticks++;
        try {
            if (System.currentTimeMillis()-started > 300000) throw new AssertionError("Timeout stage="+stage+" screen="+mc.screen);
            if (!creating && mc.screen instanceof TitleScreen) {
                creating=true; mc.options.pauseOnLostFocus=false; mc.options.renderDistance().set(3); mc.options.guiScale().set(1); mc.resizeDisplay();
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.createWorldOpenFlows().createFreshLevel("blueprint-acceptance-"+started,
                    new LevelSettings("Blueprint acceptance",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new WorldOptions(8675309,false,false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
            }
            if(!creating || ticks-entered<40) return;
            switch(stage) {
                case 0 -> {
                    if(mc.player==null || mc.getSingleplayerServer()==null) return;
                    mc.setScreen(null);
                    server(() -> seed()); next();
                }
                case 1 -> {
                    if(village==null) return;
                    blueprint=(Screen)Class.forName("forge.net.mca.client.gui.BlueprintScreen").getConstructor().newInstance();
                    mc.setScreen(blueprint);
                    // MCA's screen itself requests village and rank over its normal network on init.
                    next();
                }
                case 2 -> {
                    Header h=header(); if(h==null) return;
                    check(h.kingdomId().equals(initial),"actual civic response initial kingdom");
                    check(h.settlementName().equals("Amberford"),"settlement name in response");
                    check(!h.heraldryIcon().getPath().isBlank(),"heraldry resource in response");
                    check(!h.standingTier().isBlank(),"faction standing in response");
                    check(blueprint.getClass().getMethod("townstead$getVillage").invoke(blueprint)!=null,"real Townstead Blueprint mixin active");
                    log("viewport="+blueprint.width+"x"+blueprint.height+" widgets="+widgets()); shot("01-blueprint-initial.png");
                    clickLabel("Catalog"); next();
                }
                case 3 -> {
                    check(mc.screen==blueprint,"Townstead retains screen ownership after input");
                    check(String.valueOf(field(blueprint.getClass(),blueprint,"page")).equals("townstead_catalog"),"real catalog mouse click changed page");
                    shot("02-blueprint-catalog.png");
                    clickLabel("<< Back");
                    server(() -> {
                        var service=UltimaKingdomsApi.get(mc.getSingleplayerServer());service.setKingdom(settlement,changed);
                        var player=mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
                        UltimaFactionsApi.get(mc.getSingleplayerServer()).apply(new FactionStandingRequest(player.getUUID(),changed,400,
                            new ResourceLocation("ultima_client_test:acceptance"),FactionChangeCause.ADMIN,UUID.randomUUID(),1,Optional.of(settlement),Optional.of("Blueprint validation"),true));
                    }); next();
                }
                case 4 -> {
                    Header h=header(); if(h==null || !h.kingdomId().equals(changed))return;
                    check(mc.screen==blueprint,"same open Blueprint live-refreshes after reassignment");
                    check(!h.standingTier().equals("neutral"),"updated faction standing response="+h.standingTier());
                    log("refreshed header="+h);shot("03-blueprint-live-reassigned.png");
                    mc.options.guiScale().set(2);mc.resizeDisplay();next();
                }
                case 5 -> {
                    check(header()!=null,"civic header survives screen resize");
                    shot("04-blueprint-compact.png");
                    clickLabel("Catalog");next();
                }
                case 6 -> {
                    check(String.valueOf(field(blueprint.getClass(),blueprint,"page")).equals("townstead_catalog"),"compact screen navigation remains usable");
                    shot("05-blueprint-compact-catalog.png");
                    mc.screen.keyPressed(256,0,0);check(mc.screen==null,"Escape closes actual Blueprint");
                    check(header()==null,"closing clears header");
                    Files.writeString(output.resolve("BLUEPRINT_PASS.txt"),"PASS packaged Forge/MCA/Townstead client: actual Blueprint screen/mixin, real civic request/response name/kingdom/heraldry/faction, catalog/map mouse input, open-screen reassignment+standing refresh, compact resize/input, Escape/cache cleanup.\n");
                    server(() -> conversations.prepare(mc.getSingleplayerServer(),settlement));next();
                }
                case 7 -> {
                    if(!conversations.ready)return;
                    conversations.sendMcaPacket();next();
                }
                case 8 -> {
                    if(!conversations.receivedRefusal("MCA native crafted C2S packet"))return;
                    conversations.ready=false;server(() -> {conversations.verifyUnconsumed();conversations.newOffer();conversations.ready=true;});next();
                }
                case 9 -> {
                    if(!conversations.ready)return;
                    conversations.sendNumberedPacket();next();
                }
                case 10 -> {
                    if(!conversations.receivedRefusal("numbered choice C2S packet"))return;
                    server(() -> conversations.finishChat());next();
                }
                case 11 -> {
                    if(!conversations.finished)return;
                    Files.writeString(output.resolve("CONVERSATION_ENTRY_PASS.txt"),"PASS native MCA crafted C2S and numbered ChoiceSelectC2S both traverse actual network and return REQUIREMENTS_CHANGED after reassignment while native MCA constraints remain eligible, no offer consumed; direct chat final dispatcher also rejects.\n");
                    done=true;mc.stop();
                }
            }
        } catch(Throwable failure) { fail(failure); }
    }
    @SuppressWarnings("unchecked") private void seed() throws Exception {
        var server=mc.getSingleplayerServer();var level=server.overworld();var player=server.getPlayerList().getPlayers().get(0);BlockPos pos=player.blockPosition();
        Class<?> type=Class.forName("forge.net.mca.server.world.data.Village");Object v=type.getConstructor(int.class,ServerLevel.class).newInstance(90101,level);
        type.getMethod("setName",String.class).invoke(v,"Amberford");type.getMethod("setAutoScan",boolean.class).invoke(v,false);
        Object box=Class.forName("forge.net.mca.util.BlockBoxExtended").getConstructor(int.class,int.class,int.class,int.class,int.class,int.class).newInstance(pos.getX()-16,pos.getY()-2,pos.getZ()-16,pos.getX()+16,pos.getY()+6,pos.getZ()+16);
        Field f=type.getDeclaredField("box");f.setAccessible(true);f.set(v,box);
        Class<?> manager=Class.forName("forge.net.mca.server.world.data.VillageManager");Object m=manager.getMethod("get",ServerLevel.class).invoke(null,level);
        f=manager.getDeclaredField("villages");f.setAccessible(true);((Map<Integer,Object>)f.get(m)).put(90101,v);
        settlement=UltimaKingdomsApi.get(server).registerCandidate(level,SettlementCandidate.external(Level.OVERWORLD,pos,48,SettlementBounds.around(pos,48),new ResourceLocation("ultima_client_test:fixture"),"blueprint",initial,Map.of("mca","minecraft:overworld#90101"),"Amberford")).id();
        village=v;
    }
    private Header header() throws Exception {return (Header)field(TownsteadBlueprintHeader.class,null,"header");}
    private static Object field(Class<?> type,Object value,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private List<String> widgets(){return mc.screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).map(w->w.getMessage().getString()+"@"+w.getX()+","+w.getY()).toList();}
    private void clickLabel(String label){AbstractWidget w=mc.screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(b->b.getMessage().getString().equalsIgnoreCase(label)).findFirst().orElseThrow(()->new AssertionError("No "+label+" in "+widgets()));check(w.active,"navigation enabled "+label);check(mc.screen.mouseClicked(w.getX()+w.getWidth()/2.,w.getY()+w.getHeight()/2.,0),"mouse input handled "+label);}
    private void server(Throwing task){mc.getSingleplayerServer().execute(()->{try{task.run();}catch(Throwable e){fail(e);}});}
    private interface Throwing{void run()throws Exception;}
    private void next(){stage++;entered=ticks;log("stage="+stage);}
    private void check(boolean yes,String message){if(!yes)throw new AssertionError(message);log("PASS "+message);}
    private void log(String text){System.out.println("[BlueprintAcceptance] "+text);}
    private void shot(String name){Screenshot.grab(output.toFile(),name,mc.getMainRenderTarget(),ignored->{});}
    private void fail(Throwable e){done=true;e.printStackTrace();try{Files.writeString(output.resolve("BLUEPRINT_FAIL.txt"),e.toString()+"\n");}catch(Exception ignored){}mc.execute(mc::stop);}
}
