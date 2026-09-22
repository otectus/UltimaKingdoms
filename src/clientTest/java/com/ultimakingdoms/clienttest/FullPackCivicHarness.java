package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.api.progression.*;
import com.ultimakingdoms.civic.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in full-current-pack validation against an isolated copy of an existing world. */
public final class FullPackCivicHarness {
    private final Path output=Path.of(System.getProperty("ultima.clientTest.output"));
    private final long started=System.currentTimeMillis();
    private boolean opening,scheduled;
    private volatile boolean done;
    private volatile FullPackR3Acceptance r3Acceptance;
    private volatile FullPackR4Acceptance r4Acceptance;
    private int settled;
    private String lastScreen="";
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||done)return;
        var mc=Minecraft.getInstance();
        try {
            String screen=mc.screen==null?"none":mc.screen.getClass().getName()+" "+mc.screen.getTitle().getString();
            if(!screen.equals(lastScreen)){lastScreen=screen;System.out.println("R1_PACK_SCREEN "+screen);}
            if(System.currentTimeMillis()-started>900000)throw new IllegalStateException("Full pack world load timed out: "+mc.screen);
            var r4=r4Acceptance;if(r4!=null){r4.clientTick(mc);return;}
            var r3=r3Acceptance;if(r3!=null){r3.clientTick(mc);return;}
            if(!opening&&mc.screen instanceof TitleScreen) {
                opening=true;mc.createWorldOpenFlows().loadLevel(mc.screen,"r1-old-world-copy");
            }
            if(!scheduled&&mc.player!=null&&mc.getSingleplayerServer()!=null&&++settled>100) {
                scheduled=true;var uuid=mc.player.getUUID();var server=mc.getSingleplayerServer();
                server.execute(()->{
                    try {
                        var player=Objects.requireNonNull(server.getPlayerList().getPlayer(uuid));
                        var organizations=OrganizationApi.get(server);var definition=organizations.definition(new ResourceLocation("ultima_kingdoms:lamplighters")).orElseThrow();
                        if(definition.deeds().size()!=12)throw new AssertionError("Expected twelve adapted quests");
                        var registry=Class.forName("dev.otectus.mcaquests.data.QuestRegistry");
                        var get=registry.getMethod("get",ResourceLocation.class);
                        for(var deed:definition.deeds()) {
                            Object quest=get.invoke(null,deed.questId());
                            if(quest==null||quest instanceof Optional<?> optional&&optional.isEmpty())throw new AssertionError("Missing adapted quest: "+deed.questId());
                        }
                        var report=new StringBuilder("PASS full pack copied-world load; twelve quest IDs and civic definitions available\n");
                        if(Boolean.getBoolean("ultima.clientTest.r2Pack")) {
                            Object quest=get.invoke(null,CivicService.WORKSHOP_QUEST);
                            if(quest instanceof Optional<?> optional)quest=optional.orElseThrow();
                            if(quest==null||!(boolean)quest.getClass().getMethod("institutionalCommission").invoke(quest))throw new AssertionError("R2 institutional template unavailable");
                            Class.forName("dev.otectus.mcacrime.api.InstitutionalServiceApi").getMethod("workshop",net.minecraft.server.level.ServerPlayer.class,net.minecraft.world.entity.Entity.class);
                            Class.forName("dev.otectus.mcaquests.api.McaQuestsApi").getMethod("openInstitutionalCommissionMenu",net.minecraft.server.level.ServerPlayer.class,net.minecraft.world.entity.Entity.class,Set.class,String.class);
                            report.append("R2 institutional template and Quests/Crime API boundaries available\n");
                        }
                        if(Boolean.getBoolean("ultima.clientTest.r3Pack")) {
                            if(!com.ultimakingdoms.warfare.WarfareConfig.ENABLED.get() || !com.ultimakingdoms.warfare.WarfareConfig.MILITARY.get()
                                    || !com.ultimakingdoms.warfare.WarfareConfig.CONTRACTS.get() || !com.ultimakingdoms.warfare.WarfareConfig.WORLD_CONTEXT.get())
                                throw new AssertionError("R3 is not enabled in copied full-pack world");
                            if(!com.ultimakingdoms.compat.recruits.RecruitsMilitary.packetGuardInstalled() || !com.ultimakingdoms.compat.recruits.RecruitsEvents.available())
                                throw new AssertionError("Native R3 security hooks missing in full pack");
                            for(var kind:com.ultimakingdoms.warfare.contracts.CivilianContractKind.values()) {
                                Object quest=get.invoke(null,kind.quest()); if(quest instanceof Optional<?> optional)quest=optional.orElseThrow();
                                if(quest==null || !(boolean)quest.getClass().getMethod("institutionalCommission").invoke(quest)) throw new AssertionError("Missing R3 template "+kind);
                            }
                            Class.forName("dev.otectus.mcacrime.api.JurisdictionPolicyApi");
                            com.ultimakingdoms.api.worldcontext.WorldContextApi.get(server).orElseThrow().mapPoints(player,64);
                            var worldDefinitions=com.ultimakingdoms.worldcontext.WorldContextDefinitions.get();
                            if(worldDefinitions.structures().size()<22||worldDefinitions.encounters().size()<8)throw new AssertionError("R3 pack world definitions missing");
                            var structures=server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                            for(var id:worldDefinitions.structures().keySet())if(!structures.containsKey(id))throw new AssertionError("Unknown R3 structure "+id);
                            for(var id:worldDefinitions.encounters().keySet())if(!net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(id))throw new AssertionError("Unknown R3 encounter "+id);
                            report.append("R3 world selectors resolved: ").append(worldDefinitions.structures().size()).append(" structures, ").append(worldDefinitions.encounters().size()).append(" encounters\n");
                            report.append("R3 default features, native security hooks, six civilian contracts, Crime jurisdiction and filtered world map available\n");
                        }
                        report.append("Mods loaded: ").append(net.minecraftforge.fml.ModList.get().getMods().size()).append('\n');
                        report.append("Civic: ").append(CivicRuntime.get(server).diagnostic()).append('\n');
                        for(var predicate:List.of(new ProgressionPredicate(ProgressionPredicate.Kind.SKILL_LEVEL,"building",1),
                                new ProgressionPredicate(ProgressionPredicate.Kind.DEITY,"runic_gods:unknown_probe",0),
                                new ProgressionPredicate(ProgressionPredicate.Kind.RACE,"runic_races:human",0),
                                new ProgressionPredicate(ProgressionPredicate.Kind.LORE_COLLECTED,"ultima_probe_unknown",0))) {
                            var result=ProgressionApi.evaluate(player,predicate);
                            if(result.status()!=ProgressionResult.Status.AVAILABLE)throw new AssertionError("Installed progression read unavailable: "+predicate+" "+result);
                            report.append(predicate.kind()).append(": ").append(result.status()).append('\n');
                        }
                        CivicViews.own(player,0);server.saveEverything(false,true,true);
                        if(Boolean.getBoolean("ultima.clientTest.r3Pack")) {
                            var acceptance=new FullPackR3Acceptance(output,report,()->{
                                if(!Boolean.getBoolean("ultima.clientTest.r4Pack")){complete(report);return;}
                                r3Acceptance=null;
                                server.execute(()->{try{r4Acceptance=new FullPackR4Acceptance(output,report,()->complete(report),Objects.requireNonNull(server.getPlayerList().getPlayer(uuid)));}catch(Throwable failure){fail(failure);}});
                            });
                            acceptance.start(server,player);r3Acceptance=acceptance;
                        } else complete(report);
                    }catch(Throwable failure){fail(failure);}
                });
            }
        }catch(Throwable failure){fail(failure);}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public void serverTickStart(TickEvent.ServerTickEvent event) {
        if(event.phase==TickEvent.Phase.START)serverTick(event);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void serverTickEnd(TickEvent.ServerTickEvent event) {
        if(event.phase==TickEvent.Phase.END)serverTick(event);
    }
    private void serverTick(TickEvent.ServerTickEvent event) {
        if(done)return;var acceptance=r3Acceptance;if(acceptance==null)return;
        try { acceptance.serverTick(event); } catch(Throwable failure) { fail(failure); }
    }
    private void complete(StringBuilder report) {
        try {
            Files.writeString(output.resolve("R1_PACK_PASS.txt"),report);done=true;
            var mc=Minecraft.getInstance();mc.execute(()->{if(mc.level!=null)mc.level.disconnect();mc.clearLevel();mc.stop();});
        } catch(Throwable failure) { fail(failure); }
    }
    private void fail(Throwable failure) {
        failure.printStackTrace();done=true;var acceptance=r3Acceptance;if(acceptance!=null)acceptance.restore();
        try {Files.deleteIfExists(output.resolve("R1_PACK_PASS.txt"));Files.writeString(output.resolve("R1_PACK_FAIL.txt"),failure.toString());}catch(Exception ignored){}
        Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());
    }
}
