package com.ultimakingdoms.acceptance;
import com.ultimakingdoms.test.McaGameTests;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;
@Mod("ultima_kingdoms_acceptance")
public final class ProductionMcaHarness {
    private MinecraftServer server;
    private long tick;
    private boolean finished;
    private final TreeMap<Long,List<Runnable>> scheduled=new TreeMap<>();
    public ProductionMcaHarness(){MinecraftForge.EVENT_BUS.register(this);}
    @SubscribeEvent public void start(ServerStartedEvent event){
        server=event.getServer();
        try {
            if(Boolean.getBoolean("ultima.acceptance.commands")){
                com.ultimakingdoms.test.KingdomGameTests.runCommandScenario(server.overworld(),new BlockPos(0,65,0));
                finish("PASS packaged dispatcher: unquoted kingdom IDs/slugs, actual cross-kingdom mutation, quoted name/alias, executable suggestions");return;
            }
            McaGameTests.runScenario(server.overworld(),new BlockPos(0,65,0),
                (delay,action)->scheduled.computeIfAbsent(tick+delay,ignored->new ArrayList<>()).add(action),
                ()->finish("PASS exact MCA production lifecycle: native home/newborn origin/short visit/periodic migration/history/native data/name/NBT reload"));}
        catch(Throwable failure){fail(failure);}
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||server==null||finished)return;
        tick++;
        try {while(!scheduled.isEmpty()&&scheduled.firstKey()<=tick){for(Runnable action:scheduled.pollFirstEntry().getValue())action.run();}
            if(tick>650)throw new IllegalStateException("MCA lifecycle timed out");}
        catch(Throwable failure){fail(failure);}
    }
    private void fail(Throwable failure){failure.printStackTrace();finish("FAIL "+failure);}
    private void finish(String result){if(finished)return;finished=true;
        try{Files.writeString(Path.of(Boolean.getBoolean("ultima.acceptance.commands")?"command-results.txt":"mca-lifecycle-results.txt"),result+System.lineSeparator());}
        catch(Exception e){throw new RuntimeException(e);}System.out.println(result);server.halt(false);
    }
}
