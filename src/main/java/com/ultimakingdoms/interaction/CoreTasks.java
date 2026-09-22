package com.ultimakingdoms.interaction;

import com.ultimakingdoms.api.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import static com.ultimakingdoms.interaction.ActionRegistry.*;

public final class CoreTasks {
    static KingdomsService api(Context c){return UltimaKingdomsApi.get(c.server());}
    static void task(String id,String title,boolean operator,boolean change,List<Field> fields,Handler handler){add(new Task(id,operator?"Administration":"Settlements",title,"Choose named people and places. Administrative changes affect saved settlement identity.",operator,change,fields,c->Long.toString(api(c).revision()),handler));}
    public static void init(){
        task("settlement.here","Read this settlement",false,false,List.of(),c->api(c).getSettlementAt(c.player.serverLevel(),c.player.blockPosition()).map(s->{com.ultimakingdoms.knowledge.SettlementKnowledge.get(c.server()).discover(c.player.getUUID(),s.id());return describe(s);}).orElse("No recognized settlement here."));
        task("settlement.inspect","Read a known settlement",false,false,List.of(pick("settlement","Settlement","settlement")),c->describe(api(c).getSettlement(c.uuid("settlement")).orElseThrow()));
        task("kingdom.list","Browse kingdoms",false,false,List.of(),c->c.choices("kingdom").stream().map(Choice::label).toList());
        task("kingdom.inspect","Read a kingdom",false,false,List.of(pick("kingdom","Kingdom","kingdom")),c->c.choice("kingdom").label()+"\nKnown settlements: "+com.ultimakingdoms.knowledge.SettlementKnowledge.get(c.server()).count(c.player,api(c),c.id("kingdom")));
        task("settlement.create","Register a settlement here",true,true,List.of(number("radius","Radius (blocks)",64),text("name","Settlement name","",true)),c->{long r=c.number("radius");if(r<16||r>512)throw new IllegalArgumentException("Radius must be between 16 and 512 blocks.");var s=api(c).registerCandidate(c.player.serverLevel(),SettlementCandidate.manual(c.player.level().dimension(),c.player.blockPosition(),(int)r,new ResourceLocation("ultima_kingdoms","manual"),"gui:"+UUID.randomUUID(),c.text("name")));return "Registered "+s.displayName()+".";});
        task("settlement.rename","Rename a settlement",true,true,List.of(pick("settlement","Settlement","settlement"),text("name","New name")),c->"Renamed to "+api(c).rename(c.uuid("settlement"),c.text("name")).displayName()+".");
        task("settlement.kingdom","Change civic kingdom",true,true,List.of(pick("settlement","Settlement","settlement"),pick("kingdom","New kingdom","kingdom")),c->{api(c).setKingdom(c.uuid("settlement"),c.id("kingdom"));return "Civic kingdom updated. Native troop ownership is separate.";});
        task("settlement.reclassify","Reevaluate kingdom assignment",true,true,List.of(pick("settlement","Settlement","settlement")),c->{api(c).reclassify(c.uuid("settlement"));return "Assignment reevaluated from current definitions.";});
        for(boolean locked:new boolean[]{true,false})task("settlement."+(locked?"lock":"unlock"),(locked?"Lock":"Unlock")+" settlement identity",true,true,List.of(pick("settlement","Settlement","settlement")),c->{api(c).setLocks(c.uuid("settlement"),locked,locked);return locked?"Name and kingdom locked.":"Name and kingdom unlocked.";});
        task("settlement.discover","Discover surrounding settlements",true,false,List.of(number("radius","Search radius (chunks)",8)),c->{int r=c.integer("radius");if(r<1||r>32)throw new IllegalArgumentException("Search radius must be between 1 and 32 chunks.");return "Found "+api(c).discover(c.player.serverLevel(),c.player.blockPosition(),r).size()+" settlements.";});
        task("settlement.merge","Merge duplicate settlement records",true,true,List.of(pick("source","Record to retire","settlement"),pick("target","Record to keep","settlement")),c->{api(c).merge(c.uuid("source"),c.uuid("target"));return "Settlement records merged; the retained settlement keeps its identity.";});
        task("settlement.debug","Inspect settlement assignment evidence",true,false,List.of(pick("settlement","Settlement","settlement")),c->{var s=api(c).getSettlement(c.uuid("settlement")).orElseThrow();return describe(s)+"\n"+String.join("\n",s.assignmentTrace());});
        task("citizen.inspect","Inspect civic residence",true,false,List.of(pick("person","Person","person")),c->api(c).getCivicIdentity(BasicTargets.entity(c,"person")).map(v->"Origin: "+v.originSettlement().map(id->BasicTargets.settlement(c,id)).orElse("Unknown")+"\nResidence: "+v.residenceSettlement().map(id->BasicTargets.settlement(c,id)).orElse("Unknown")).orElse("No confirmed civic identity."));
        for(boolean origin:new boolean[]{true,false})task("citizen."+(origin?"origin":"residence"),origin?"Set civic origin":"Set civic residence",true,true,List.of(pick("person","Person","person"),pick("settlement","Settlement","settlement")),c->{var entity=BasicTargets.entity(c,"person");if(origin)api(c).setOrigin(entity,c.uuid("settlement"));else api(c).setResidence(entity,c.uuid("settlement"));return "Civic record updated; native home and family are unchanged.";});
        task("definitions.reload","Reload server definitions",true,true,List.of(),c->{return new Pending(c.server().reloadResources(c.server().getPackRepository().getSelectedIds()).thenApply(ignored->"Server definitions reloaded successfully."),"Reload requested. Use Check this action to see the final success or failure.");});
    }
    static String describe(SettlementView s){return s.displayName()+"\nCivic kingdom: "+words(s.kingdomId().getPath())+"\nLocation: "+s.anchor().toShortString()+"\nDimension: "+s.dimension().location()+"\nAssignment: "+words(s.assignmentSource().name());}
    private CoreTasks(){}
}
