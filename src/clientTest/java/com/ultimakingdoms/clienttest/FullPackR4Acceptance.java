package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.politics.Politics;
import com.ultimakingdoms.api.politics.UltimaPoliticsApi;
import com.ultimakingdoms.client.politics.KingdomScreen;
import com.ultimakingdoms.evolution.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.UUID;

/** Real network-backed history rendering after an isolated existing-world upgrade. */
public final class FullPackR4Acceptance {
    private final Path output;
    private final StringBuilder report;
    private final Runnable finish;
    private final String kingdom, settlement;
    private boolean opened, requested;
    private int ticks, captureAt, readyAt;
    public FullPackR4Acceptance(Path output, StringBuilder report, Runnable finish, ServerPlayer player) {
        this.output = output; this.report = report; this.finish = finish;
        var server = player.getServer();
        var place = UltimaKingdomsApi.get(server).getSettlementAt(player.serverLevel(), player.blockPosition()).orElseThrow();
        kingdom = place.kingdomId().toString(); settlement = place.id().toString();
        if (EvolutionDefinitions.INSTANCE.snapshot().size() < 6) throw new AssertionError("R4 authored scenario catalog unavailable");
        if (com.ultimakingdoms.evolution.drama.DramaDefinitions.INSTANCE.snapshot().size() < 4) throw new AssertionError("R4 authored drama catalog unavailable");
        if (!EvolutionSavedData.get(server).writable() || !ProtectionSavedData.get(server).writable() || !RecruitTransferData.get(server).writable())
            throw new AssertionError("R4 sidecars are not writable after upgrade");
        var politics=UltimaPoliticsApi.get(server);
        if(politics.government(kingdom).isEmpty()) {
            var founded=politics.execute(player,new Politics.Request(UUID.randomUUID(),politics.revision(),Politics.Action.BOOTSTRAP,kingdom,kingdom+"_charter",settlement,
                    new Politics.Person(player.getUUID(),Politics.Kind.PLAYER),"","","",0,0,0));
            if(!founded.success())throw new AssertionError("Copied-world history fixture: "+founded.message());
        }
        var page = politics.page(player, UUID.randomUUID(), kingdom, "history", 0);
        if (!page.tab().equals("history")) throw new AssertionError("R4 history endpoint unavailable");
        report.append("R4 catalogs, optional sidecars and viewer-filtered history available in copied full-pack world\n");
    }
    public void clientTick(Minecraft mc) throws Exception {
        if (++ticks > 300) throw new AssertionError("R4 history screen timed out");
        if (!opened) {
            var screen = new KingdomScreen(null, kingdom, settlement); mc.setScreen(screen);
            Field tab = KingdomScreen.class.getDeclaredField("tab"); tab.setAccessible(true); tab.set(screen, "history");
            var request = KingdomScreen.class.getDeclaredMethod("request", Politics.Request.class); request.setAccessible(true); request.invoke(screen, new Object[]{null});
            opened = true; return;
        }
        if (!(mc.screen instanceof KingdomScreen screen)) throw new AssertionError("R4 history screen unexpectedly closed");
        Field field = KingdomScreen.class.getDeclaredField("page"); field.setAccessible(true); var page = (Politics.Page)field.get(screen);
        if (page == null || !page.tab().equals("history")) return;
        if (!page.kingdom().equals(kingdom)) throw new AssertionError("Wrong kingdom in history reply");
        if(readyAt==0){readyAt=ticks;return;}
        if(ticks<readyAt+5)return;
        if (!requested) {
            if (((net.minecraft.client.gui.screens.Screen) screen).children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.Button)
                    .map(c -> ((net.minecraft.client.gui.components.Button)c).getMessage().getString()).noneMatch("History"::equals))
                throw new AssertionError("History tab missing or untranslated");
            Screenshot.grab(output.toFile(), "r4-political-history.png", mc.getMainRenderTarget(), message -> System.out.println("R4_PACK " + message.getString()));
            requested = true; captureAt = ticks; return;
        }
        if (ticks > captureAt + 3 && Files.isRegularFile(output.resolve("screenshots/r4-political-history.png"))) {
            report.append("R4 real-client History tab received authorized network page; screenshots/r4-political-history.png\n"); finish.run();
        }
    }
}
