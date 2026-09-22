package com.ultimakingdoms.worldcontext;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WorldContextSavedDataTest {
    @Test void futureSchemaIsPreservedAndReadOnly(){
        CompoundTag future=new CompoundTag();future.putInt("Schema",99);future.putString("Payload","{\"future\":true}");
        var loaded=WorldContextSavedData.load(future);assertFalse(loaded.writable());assertEquals(future,loaded.save(new CompoundTag()));
    }
    @Test void routeEvidenceRoundTripsWithoutInventingDiscovery(){
        UUID first=UUID.randomUUID(),second=UUID.randomUUID(),route=UUID.randomUUID(),from=UUID.randomUUID(),to=UUID.randomUUID();
        var state=new WorldContextSavedData.State();state.revision=4;
        state.sites.put(first.toString(),new WorldContextSavedData.Site(first,"minecraft:overworld",1,64,2,"settlement","minecraft:village_plains","loaded_structure_start",20,Set.of(UUID.randomUUID()),false));
        state.sites.put(second.toString(),new WorldContextSavedData.Site(second,"minecraft:overworld",30,64,2,"trade_post","ultima_kingdoms:operator_authored","operator:test",21,Set.of(),true));
        state.routes.put(route.toString(),new WorldContextSavedData.Route(route,from,to,UUID.randomUUID(),UUID.randomUUID(),List.of(
                new WorldContextSavedData.Point("minecraft:overworld",1,64,2,"from"),new WorldContextSavedData.Point("minecraft:overworld",30,64,2,"to")),30));
        CompoundTag tag=new CompoundTag();tag.putInt("Schema",WorldContextSavedData.SCHEMA);tag.putString("Payload",WorldContextSavedData.JSON.toJson(state));
        var loaded=WorldContextSavedData.load(tag);assertTrue(loaded.writable());assertEquals(4,loaded.revision());
        assertTrue(loaded.site(second).orElseThrow().discoverers().isEmpty(),"operator authorship must not fake player discovery");
        assertEquals(2,loaded.route(route).orElseThrow().checkpoints().size());
    }
    @Test void encounterEntityAndReceiptDedupeSurviveReload(){
        UUID site=UUID.randomUUID(),entity=UUID.randomUUID(),receipt=UUID.randomUUID(),player=UUID.randomUUID();var state=new WorldContextSavedData.State();
        state.sites.put(site.toString(),new WorldContextSavedData.Site(site,"minecraft:overworld",0,64,0,"hostile_outpost","minecraft:pillager_outpost","loaded_structure_start",1,Set.of(player),false));
        state.encounters.put(receipt.toString(),new WorldContextSavedData.Encounter(receipt,player,entity,site,"minecraft:evoker",12,1,100));state.creditedEntities.add(entity.toString());
        CompoundTag tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Payload",WorldContextSavedData.JSON.toJson(state));
        var loaded=WorldContextSavedData.load(tag);assertTrue(loaded.credited(entity));assertEquals(receipt,loaded.encounters().iterator().next().receipt());
    }
    @Test void hotPathIndexesBoundSitesAndSelectOnlyActivePlayerJourneysAndRecentReceipts(){
        UUID player=UUID.randomUUID(),other=UUID.randomUUID(),institutionA=UUID.randomUUID(),institutionB=UUID.randomUUID(),settlement=UUID.randomUUID();
        var state=new WorldContextSavedData.State();List<UUID> sites=new ArrayList<>();
        for(int i=0;i<100;i++){UUID id=UUID.randomUUID();sites.add(id);state.sites.put(id.toString(),new WorldContextSavedData.Site(id,"minecraft:overworld",
                i%4,64,i%4,"site","minecraft:village_plains","loaded_structure_start",i,Set.of(player),false));}
        UUID activeRoute=UUID.randomUUID(),completeRoute=UUID.randomUUID();for(UUID route:List.of(activeRoute,completeRoute))state.routes.put(route.toString(),
                new WorldContextSavedData.Route(route,institutionA,institutionB,settlement,settlement,List.of(
                        new WorldContextSavedData.Point("minecraft:overworld",0,64,0,"from"),new WorldContextSavedData.Point("minecraft:overworld",8,64,8,"to")),1));
        state.journeys.put(WorldContextSavedData.journeyKey(player,activeRoute),new WorldContextSavedData.Journey(player,activeRoute,0,1,1,false));
        state.journeys.put(WorldContextSavedData.journeyKey(player,completeRoute),new WorldContextSavedData.Journey(player,completeRoute,2,1,2,true));
        state.journeys.put(WorldContextSavedData.journeyKey(other,activeRoute),new WorldContextSavedData.Journey(other,activeRoute,0,1,1,false));
        for(long at:List.of(10L,100L,200L)){UUID receipt=UUID.randomUUID();state.encounters.put(receipt.toString(),new WorldContextSavedData.Encounter(
                receipt,player,UUID.randomUUID(),sites.get(0),"minecraft:evoker",1,1,at));}
        CompoundTag tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Payload",WorldContextSavedData.JSON.toJson(state));var loaded=WorldContextSavedData.load(tag);
        assertEquals(7,loaded.nearbySites("minecraft:overworld",0,0,8,0,7).size());
        assertEquals(1,loaded.activeJourneys(player).size());assertEquals(activeRoute,loaded.activeJourneys(player).get(0).route());
        assertEquals(2,loaded.recentEncounters(player,sites.get(0),"minecraft:evoker",200,100));
    }
    @Test void nearestKnownSiteIsDeterministicAndFailsClosedBeforeSampling(){
        UUID player=UUID.randomUUID();var state=new WorldContextSavedData.State();
        UUID farther=UUID.fromString("00000000-0000-0000-0000-000000000030");
        UUID tiedLater=UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID tiedFirst=UUID.fromString("00000000-0000-0000-0000-000000000010");
        for(var entry:List.of(Map.entry(farther,new int[]{4,64,0}),Map.entry(tiedLater,new int[]{1,64,0}),
                Map.entry(tiedFirst,new int[]{-1,64,0}))){int[] p=entry.getValue();state.sites.put(entry.getKey().toString(),
                new WorldContextSavedData.Site(entry.getKey(),"minecraft:overworld",p[0],p[1],p[2],"site",
                        "minecraft:village_plains","loaded_structure_start",1,Set.of(player),false));}
        CompoundTag tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Payload",WorldContextSavedData.JSON.toJson(state));
        var loaded=WorldContextSavedData.load(tag);
        assertTrue(loaded.nearestKnownSite("minecraft:overworld",0,64,0,8,player,2).isEmpty(),
                "candidate pressure must fail closed instead of sampling a possibly farther site");
        assertEquals(tiedFirst,loaded.nearestKnownSite("minecraft:overworld",0,64,0,8,player,3).orElseThrow().id(),
                "distance ties use stable site identity order");
    }
}
