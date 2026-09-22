package com.ultimakingdoms.worldcontext;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.politics.InstitutionView;
import com.ultimakingdoms.api.politics.UltimaPoliticsApi;
import com.ultimakingdoms.api.warfare.WarfareApi;
import com.ultimakingdoms.api.worldcontext.WorldContextApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** All mutations happen on the server thread and cross the saved-data durability fence before success. */
public final class WorldContextService implements WorldContextApi.Provider {
    private static final long REPEAT_WINDOW = 7L * 24000L;
    private static final int PROGRESS_BUDGET = 8, SITE_DISCOVERY_BUDGET = 64, ENCOUNTER_SITE_BUDGET = 256;
    private final MinecraftServer server; private final WorldContextSavedData data;
    public WorldContextService(MinecraftServer server){this.server=server;this.data=WorldContextSavedData.get(server);}
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("World context requires server thread");}

    public boolean observeStructure(ServerPlayer player, ResourceLocation structure, BlockPos anchor, String role){
        thread(); if(!enabled()||!data.writable())return false;
        String dimension=player.level().dimension().location().toString();
        String identity=dimension+"|"+structure+"|"+(anchor.getX()>>4)+"|"+(anchor.getZ()>>4);
        UUID id=UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        var old=data.site(id); if(old.isPresent()&&old.get().discoverers().contains(player.getUUID()))return false;
        var next=data.snapshot(); WorldContextSavedData.Site site=old.orElseGet(()->new WorldContextSavedData.Site(id,dimension,
                anchor.getX(),anchor.getY(),anchor.getZ(),role,structure.toString(),"loaded_structure_start",player.level().getGameTime(),Set.of(),false));
        if(next.sites.size()>=WorldContextSavedData.SITE_LIMIT&&!next.sites.containsKey(id.toString()))return false;
        next.sites.put(id.toString(),site.discovered(player.getUUID()));next.revision++;return data.commit(server,next,WorldContextSavedData.Index.SITES);
    }

    public Optional<UUID> authorSite(ServerPlayer operator,String role){
        thread();if(!enabled()||!operator.hasPermissions(2)||!data.writable()||data.sites().size()>=WorldContextSavedData.SITE_LIMIT)return Optional.empty();
        BlockPos pos=operator.blockPosition();String dimension=operator.level().dimension().location().toString();
        UUID id=UUID.randomUUID();var next=data.snapshot();next.sites.put(id.toString(),new WorldContextSavedData.Site(id,dimension,
                pos.getX(),pos.getY(),pos.getZ(),role,"ultima_kingdoms:operator_authored","operator:"+operator.getUUID(),
                operator.level().getGameTime(),Set.of(),true));next.revision++;
        return data.commit(server,next,WorldContextSavedData.Index.SITES)?Optional.of(id):Optional.empty();
    }

    /** An authored site becomes usable only when a player physically encounters its loaded position. */
    public void discoverAuthoredNearby(ServerPlayer player){
        thread();if(!enabled())return;String dimension=player.level().dimension().location().toString();BlockPos pos=player.blockPosition();
        long pass=Math.floorDiv((long)player.tickCount,20L);List<WorldContextSavedData.Site> found=data.nearbySites(dimension,pos.getX(),pos.getZ(),8,
                pass*SITE_DISCOVERY_BUDGET,SITE_DISCOVERY_BUDGET).stream().filter(WorldContextSavedData.Site::operatorAuthored)
                .filter(s->s.dimension().equals(dimension)&&!s.discoverers().contains(player.getUUID()))
                .filter(s->pos.distSqr(new BlockPos(s.x(),s.y(),s.z()))<=64).limit(4).toList();
        if(found.isEmpty()||!data.writable())return;var next=data.snapshot();
        found.forEach(s->next.sites.put(s.id().toString(),s.discovered(player.getUUID())));next.revision++;data.commit(server,next,WorldContextSavedData.Index.SITES);
    }

    public Optional<UUID> authorRoute(ServerPlayer operator,UUID fromId,UUID toId){
        thread();if(!enabled()||fromId.equals(toId)||!operator.hasPermissions(2)||!data.writable()||data.routes().size()>=WorldContextSavedData.ROUTE_LIMIT)return Optional.empty();
        Map<UUID,InstitutionView> known;
        try{known=knownInstitutions(operator,Set.of(fromId,toId));}catch(RuntimeException unavailable){return Optional.empty();}
        InstitutionView from=known.get(fromId),to=known.get(toId);
        if(from!=null&&!from.operational())from=null;if(to!=null&&!to.operational())to=null;
        if(from==null||to==null||from.dimension().equals(to.dimension())==false)return Optional.empty();
        if(siteAt(from,operator.getUUID()).isEmpty()||siteAt(to,operator.getUUID()).isEmpty())return Optional.empty();
        BlockPos middle=new BlockPos((from.position().getX()+to.position().getX())/2,
                (from.position().getY()+to.position().getY())/2,(from.position().getZ()+to.position().getZ())/2);
        String dimension=from.dimension().toString();List<WorldContextSavedData.Point> points=List.of(
                point(dimension,from.position(),"Departure: "+from.type()),point(dimension,middle,"Route checkpoint"),
                point(dimension,to.position(),"Arrival: "+to.type()));UUID id=UUID.randomUUID();
        var next=data.snapshot();next.routes.put(id.toString(),new WorldContextSavedData.Route(id,fromId,toId,from.settlement(),to.settlement(),points,server.overworld().getGameTime()));
        next.revision++;return data.commit(server,next)?Optional.of(id):Optional.empty();
    }

    public boolean authorNeutralResource(ServerPlayer operator,UUID siteId,UUID returnId,Set<ResourceLocation> commodities,Set<ResourceLocation> exclusions){
        thread();if(!enabled()||!operator.hasPermissions(2)||!data.writable()||commodities.isEmpty())return false;
        var site=data.site(siteId);var back=data.site(returnId);if(site.isEmpty()||back.isEmpty()||!site.get().discoverers().contains(operator.getUUID())
                ||!back.get().discoverers().contains(operator.getUUID()))return false;
        ServerLevel level=level(site.get().dimension());if(level==null||UltimaKingdomsApi.get(server).getSettlementAt(level,pos(site.get())).isPresent())return false;
        var next=data.snapshot();next.resources.put(siteId.toString(),new WorldContextSavedData.ResourceAccess(siteId,returnId,
                commodities.stream().map(ResourceLocation::toString).collect(java.util.stream.Collectors.toSet()),
                exclusions.stream().map(ResourceLocation::toString).collect(java.util.stream.Collectors.toSet())));next.revision++;
        return data.commit(server,next);
    }

    @Override public WorldContextApi.Action beginRoute(ServerPlayer player,UUID routeId){
        thread();if(!enabled())return WorldContextApi.Action.deny("disabled");var route=data.route(routeId);if(route.isEmpty()||!routeVisible(player,route.get()))return WorldContextApi.Action.deny("route_unknown");
        String access=access(player,route.get());if(!access.equals("available"))return WorldContextApi.Action.deny(access);
        var next=data.snapshot();next.journeys.put(WorldContextSavedData.journeyKey(player.getUUID(),routeId),
                new WorldContextSavedData.Journey(player.getUUID(),routeId,0,player.level().getGameTime(),player.level().getGameTime(),false));next.revision++;
        return data.commit(server,next,WorldContextSavedData.Index.JOURNEYS)?new WorldContextApi.Action(true,"started",Optional.of(routeId)):WorldContextApi.Action.deny("save_failed");
    }

    public void progress(ServerPlayer player){
        thread();if(!enabled())return;
        var journeys=data.activeJourneys(player.getUUID());
        if(journeys.isEmpty())return;
        var advanced=new ArrayList<WorldContextSavedData.Journey>();WorldContextSavedData.State next=null;
        int checked=Math.min(PROGRESS_BUDGET,journeys.size());int start=progressStart(player.tickCount,journeys.size());
        for(int i=0;i<checked;i++){
            var journey=journeys.get((start+i)%journeys.size());var route=data.route(journey.route());
            if(route.isEmpty()||!access(player,route.get()).equals("available")||journey.next()>=route.get().checkpoints().size())continue;
            var checkpoint=route.get().checkpoints().get(journey.next());
            if(!checkpoint.dimension().equals(player.level().dimension().location().toString())
                    ||player.blockPosition().distSqr(new BlockPos(checkpoint.x(),checkpoint.y(),checkpoint.z()))>64)continue;
            int nextIndex=journey.next()+1;boolean complete=nextIndex==route.get().checkpoints().size();
            var update=new WorldContextSavedData.Journey(player.getUUID(),journey.route(),nextIndex,journey.startedAt(),player.level().getGameTime(),complete);
            if(next==null)next=data.snapshot();next.journeys.put(WorldContextSavedData.journeyKey(player.getUUID(),journey.route()),update);advanced.add(update);
        }
        if(next==null)return;next.revision++;if(!data.commit(server,next,WorldContextSavedData.Index.JOURNEYS))return;
        for(var journey:advanced)player.sendSystemMessage(net.minecraft.network.chat.Component.literal(journey.complete()?"Route completed safely.":"Route checkpoint "+journey.next()+" reached."));
    }
    static int progressStart(int playerTickCount,int journeys){
        if(journeys<1)throw new IllegalArgumentException("journey count must be positive");
        long playerPass=Math.floorDiv((long)playerTickCount,20L);return Math.floorMod(playerPass*PROGRESS_BUDGET,journeys);
    }

    @Override public WorldContextApi.Action requestNeutralResourceAccess(ServerPlayer player,UUID siteId,ResourceLocation commodity){
        thread();if(!com.ultimakingdoms.warfare.WarfareConfig.ENABLED.get()||!com.ultimakingdoms.warfare.WarfareConfig.WORLD_CONTEXT.get())return WorldContextApi.Action.deny("disabled");var policy=data.resource(siteId);if(policy.isEmpty())return WorldContextApi.Action.deny("resource_site_unknown");
        var p=policy.get();if(!known(player,p.site())||!known(player,p.returnSite()))return WorldContextApi.Action.deny("return_path_unknown");
        if(p.exclusions().contains(commodity.toString()))return WorldContextApi.Action.deny("commodity_excluded");
        if(!p.commodities().contains(commodity.toString()))return WorldContextApi.Action.deny("commodity_unrecognized");
        return new WorldContextApi.Action(true,"neutral_access_with_return_path",Optional.empty());
    }

    public boolean recordEncounter(Entity entity,Map<ServerPlayer,Integer> contributions,WorldContextDefinitions.EncounterRule rule){
        thread();if(!enabled()||!data.writable()||data.credited(entity.getUUID())||contributions.isEmpty())return false;
        ResourceLocation type=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());long now=entity.level().getGameTime();var next=data.snapshot();int added=0;
        if(next.creditedEntities.size()>=WorldContextSavedData.RECEIPT_LIMIT||next.encounters.size()>=WorldContextSavedData.RECEIPT_LIMIT)return false;
        int room=WorldContextSavedData.RECEIPT_LIMIT-next.encounters.size();
        for(var entry:contributions.entrySet()){
            if(added>=room)break;ServerPlayer player=entry.getKey();
            if(player.level()!=entity.level())continue;var site=nearestKnownSite(player,entity,128);if(site.isEmpty())continue;
            int prior=data.recentEncounters(player.getUUID(),site.get().id(),type.toString(),now,REPEAT_WINDOW);if(prior>=rule.repeatBudget())continue;
            UUID receipt=UUID.randomUUID();next.encounters.put(receipt.toString(),new WorldContextSavedData.Encounter(receipt,player.getUUID(),entity.getUUID(),
                    site.get().id(),type.toString(),Math.max(1,entry.getValue()),prior+1,now));added++;
        }
        if(added==0)return false;next.creditedEntities.add(entity.getUUID().toString());next.revision++;return data.commit(server,next,WorldContextSavedData.Index.ENCOUNTERS);
    }

    @Override public List<WorldContextApi.Site> sites(ServerPlayer viewer,int offset,int limit){
        thread();bounds(offset,limit);return data.sites().stream().filter(s->viewer.hasPermissions(2)||s.discoverers().contains(viewer.getUUID()))
                .sorted(Comparator.comparing(WorldContextSavedData.Site::createdAt).thenComparing(WorldContextSavedData.Site::id))
                .skip(offset).limit(limit).map(s->view(viewer,s)).toList();
    }
    @Override public List<WorldContextApi.Route> routes(ServerPlayer viewer,int offset,int limit){
        thread();bounds(offset,limit);return data.routes().stream().filter(r->routeVisible(viewer,r)).sorted(Comparator.comparing(WorldContextSavedData.Route::id))
                .skip(offset).limit(limit).map(r->routeView(viewer,r)).toList();
    }
    @Override public List<WorldContextApi.Encounter> encounters(ServerPlayer viewer,int offset,int limit){
        thread();bounds(offset,limit);return data.encounters().stream().filter(e->viewer.hasPermissions(2)||e.player().equals(viewer.getUUID()))
                .sorted(Comparator.comparing(WorldContextSavedData.Encounter::completedAt).reversed()).skip(offset).limit(limit)
                .map(e->new WorldContextApi.Encounter(e.receipt(),new ResourceLocation(e.encounter()),e.entity(),e.site(),e.contribution(),e.repeat(),e.completedAt())).toList();
    }
    @Override public List<WorldContextApi.MapPoint> mapPoints(ServerPlayer viewer,int limit){
        thread();if(limit<1||limit>128)throw new IllegalArgumentException("invalid map point limit");List<WorldContextApi.MapPoint> result=new ArrayList<>();
        for(var site:sites(viewer,0,Math.min(64,limit)))result.add(new WorldContextApi.MapPoint("site:"+site.id(),site.dimension(),site.position(),site.role(),
                site.contested()?"contested":"site",false,false));
        for(var route:routes(viewer,0,32))if(route.active()&&route.accessible()&&result.size()<limit){var point=route.checkpoints().get(route.nextCheckpoint());
            result.add(new WorldContextApi.MapPoint("route:"+route.id(),point.dimension(),point.position(),point.label(),"route",false,false));}
        return List.copyOf(result.subList(0,Math.min(limit,result.size())));
    }
    @Override public long revision(){return data.revision();}

    private WorldContextApi.Site view(ServerPlayer viewer,WorldContextSavedData.Site site){
        String kingdom="",controller="";boolean contested=false;ServerLevel level=level(site.dimension());
        if(level!=null){var settlement=UltimaKingdomsApi.get(server).getSettlementAt(level,pos(site));if(settlement.isPresent()){
            kingdom=settlement.get().kingdomId().toString();var control=WarfareApi.get(server).flatMap(w->w.control(viewer,settlement.get().id())).filter(c->c.availability().equals("available"));
            if(control.isPresent()){kingdom=control.get().recognizedKingdom();controller=control.get().nativeController();contested=control.get().contested();}
        }}
        return new WorldContextApi.Site(site.id(),new ResourceLocation(site.dimension()),pos(site),site.role(),new ResourceLocation(site.provenance()),site.evidence(),site.createdAt(),kingdom,controller,contested);
    }
    private WorldContextApi.Route routeView(ServerPlayer viewer,WorldContextSavedData.Route route){
        var journey=data.journey(viewer.getUUID(),route.id());int next=journey.map(WorldContextSavedData.Journey::next).orElse(0);
        boolean active=journey.filter(j->!j.complete()).isPresent();String status=access(viewer,route);
        return new WorldContextApi.Route(route.id(),route.fromInstitution(),route.toInstitution(),route.checkpoints().stream().map(p->new WorldContextApi.Checkpoint(new ResourceLocation(p.dimension()),new BlockPos(p.x(),p.y(),p.z()),p.label())).toList(),
                Math.min(next,route.checkpoints().size()-1),active,status.equals("available"),journey.filter(WorldContextSavedData.Journey::complete).isPresent()?"complete":status);
    }
    private String access(ServerPlayer player,WorldContextSavedData.Route route){
        if(!com.ultimakingdoms.warfare.WarfareConfig.ENABLED.get()||!com.ultimakingdoms.warfare.WarfareConfig.WORLD_CONTEXT.get())return "disabled";
        var warfare=WarfareApi.get(server);if(warfare.isEmpty())return "warfare_unavailable";
        if(!warfare.get().safeConduct(player,route.fromSettlement())||!warfare.get().safeConduct(player,route.toSettlement()))return "safe_conduct_denied";
        try{var politics=UltimaPoliticsApi.get(server);if(politics.institution(route.fromInstitution()).filter(InstitutionView::operational).isEmpty()
                ||politics.institution(route.toInstitution()).filter(InstitutionView::operational).isEmpty())return "institution_unavailable";
        }catch(RuntimeException unavailable){return "politics_unavailable";}return "available";
    }
    private boolean routeVisible(ServerPlayer player,WorldContextSavedData.Route route){
        try{var known=knownInstitutions(player,Set.of(route.fromInstitution(),route.toInstitution()));return known.size()==2;}
        catch(RuntimeException unavailable){return false;}
    }
    private Map<UUID,InstitutionView> knownInstitutions(ServerPlayer player,Set<UUID> wanted){
        Map<UUID,InstitutionView> found=new HashMap<>();var politics=UltimaPoliticsApi.get(server);
        for(int offset=0;offset<=4096&&found.size()<wanted.size();offset+=32){
            var page=politics.knownInstitutions(player,offset,32);
            for(var institution:page)if(wanted.contains(institution.id()))found.put(institution.id(),institution);
            if(page.size()<32)break;
        }
        return found;
    }
    private Optional<WorldContextSavedData.Site> siteAt(InstitutionView institution,UUID player){return data.sites().stream()
            .filter(s->s.dimension().equals(institution.dimension().toString())&&s.discoverers().contains(player))
            .filter(s->pos(s).distSqr(institution.position())<=4096).min(Comparator.comparingDouble(s->pos(s).distSqr(institution.position())));}
    private Optional<WorldContextSavedData.Site> nearestKnownSite(ServerPlayer player,Entity encounter,int radius){
        BlockPos position=encounter.blockPosition();String dimension=encounter.level().dimension().location().toString();
        return data.nearestKnownSite(dimension,position.getX(),position.getY(),position.getZ(),radius,
                player.getUUID(),ENCOUNTER_SITE_BUDGET);}
    private boolean known(ServerPlayer player,UUID site){return player.hasPermissions(2)||data.site(site).map(s->s.discoverers().contains(player.getUUID())).orElse(false);}
    private ServerLevel level(String dimension){return server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,new ResourceLocation(dimension)));}
    private static BlockPos pos(WorldContextSavedData.Site site){return new BlockPos(site.x(),site.y(),site.z());}
    private static WorldContextSavedData.Point point(String dimension,BlockPos pos,String label){return new WorldContextSavedData.Point(dimension,pos.getX(),pos.getY(),pos.getZ(),label);}
    private static boolean enabled(){return com.ultimakingdoms.warfare.WarfareConfig.ENABLED.get()
            &&com.ultimakingdoms.warfare.WarfareConfig.WORLD_CONTEXT.get();}
    private static void bounds(int offset,int limit){if(offset<0||offset>100000||limit<1||limit>64)throw new IllegalArgumentException("invalid page");}
}
