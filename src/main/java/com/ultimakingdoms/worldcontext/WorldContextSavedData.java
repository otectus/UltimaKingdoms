package com.ultimakingdoms.worldcontext;

import com.google.gson.*;
import com.ultimakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.util.*;

/** Bounded R3 place, route and encounter evidence. A future/corrupt schema is preserved read-only. */
public final class WorldContextSavedData extends SavedData {
    public static final String NAME = "ultima_kingdoms_world_context";
    public static final int SCHEMA = 1;
    static final int SITE_LIMIT = 8192, ROUTE_LIMIT = 2048, JOURNEY_LIMIT = 16384, RECEIPT_LIMIT = 32768;
    private static final int SITE_BUCKET_SIZE = 64;
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    public record Point(String dimension, int x, int y, int z, String label) {
        public Point { token(dimension, 128); text(label, 96); }
    }
    public record Site(UUID id, String dimension, int x, int y, int z, String role, String provenance,
                       String evidence, long createdAt, Set<UUID> discoverers, boolean operatorAuthored) {
        public Site { Objects.requireNonNull(id); token(dimension,128); token(role,48); token(provenance,128);
            text(evidence,256); discoverers=Set.copyOf(discoverers); if(createdAt<0||discoverers.size()>4096)bad(); }
        Site discovered(UUID player) { var next=new HashSet<>(discoverers); next.add(player);
            return new Site(id,dimension,x,y,z,role,provenance,evidence,createdAt,next,operatorAuthored); }
    }
    public record Route(UUID id, UUID fromInstitution, UUID toInstitution, UUID fromSettlement,
                        UUID toSettlement, List<Point> checkpoints, long createdAt) {
        public Route { Objects.requireNonNull(id);Objects.requireNonNull(fromInstitution);Objects.requireNonNull(toInstitution);
            Objects.requireNonNull(fromSettlement);Objects.requireNonNull(toSettlement);checkpoints=List.copyOf(checkpoints);
            if(fromInstitution.equals(toInstitution)||checkpoints.size()<2||checkpoints.size()>16||createdAt<0)bad(); }
    }
    public record Journey(UUID player, UUID route, int next, long startedAt, long updatedAt, boolean complete) {
        public Journey { Objects.requireNonNull(player);Objects.requireNonNull(route);
            if(next<0||next>16||startedAt<0||updatedAt<startedAt)bad(); }
    }
    public record ResourceAccess(UUID site, UUID returnSite, Set<String> commodities, Set<String> exclusions) {
        public ResourceAccess { Objects.requireNonNull(site);Objects.requireNonNull(returnSite);
            commodities=Set.copyOf(commodities);exclusions=Set.copyOf(exclusions);
            if(commodities.isEmpty()||commodities.size()>64||exclusions.size()>64)bad();
            commodities.forEach(v->token(v,128)); exclusions.forEach(v->token(v,128)); }
    }
    public record Encounter(UUID receipt, UUID player, UUID entity, UUID site, String encounter,
                            int contribution, int repeat, long completedAt) {
        public Encounter { Objects.requireNonNull(receipt);Objects.requireNonNull(player);Objects.requireNonNull(entity);
            Objects.requireNonNull(site);token(encounter,128);if(contribution<1||repeat<1||completedAt<0)bad(); }
    }
    static final class State {
        long revision;
        Map<String,Site> sites=new HashMap<>(); Map<String,Route> routes=new HashMap<>();
        Map<String,Journey> journeys=new HashMap<>(); Map<String,ResourceAccess> resources=new HashMap<>();
        Map<String,Encounter> encounters=new HashMap<>(); Set<String> creditedEntities=new HashSet<>();
        void validate(){
            if(revision<0||sites.size()>SITE_LIMIT||routes.size()>ROUTE_LIMIT||journeys.size()>JOURNEY_LIMIT
                    ||resources.size()>SITE_LIMIT||encounters.size()>RECEIPT_LIMIT||creditedEntities.size()>RECEIPT_LIMIT)bad();
            sites.forEach((k,v)->{if(!k.equals(v.id().toString()))bad();});
            routes.forEach((k,v)->{if(!k.equals(v.id().toString()))bad();});
            journeys.forEach((k,v)->{if(!k.equals(journeyKey(v.player(),v.route()))||!routes.containsKey(v.route().toString()))bad();});
            resources.forEach((k,v)->{if(!k.equals(v.site().toString())||!sites.containsKey(k)||!sites.containsKey(v.returnSite().toString()))bad();});
            encounters.forEach((k,v)->{if(!k.equals(v.receipt().toString())||!sites.containsKey(v.site().toString()))bad();});
            if(creditedEntities.stream().anyMatch(v->v.length()!=36))bad();
        }
    }

    record SiteBucket(String dimension,int x,int z) { }
    record EncounterKey(UUID player,UUID site,String encounter) { }
    enum Index { SITES, JOURNEYS, ENCOUNTERS }
    private State state=new State(); private CompoundTag preserved; private String diagnostic="";
    private transient Map<SiteBucket,List<Site>> sitesByBucket=Map.of();
    private transient Map<UUID,List<Journey>> journeysByPlayer=Map.of();
    private transient Map<EncounterKey,List<Long>> encounterTimes=Map.of();
    public static WorldContextSavedData get(MinecraftServer server){
        if(!server.isSameThread())throw new IllegalStateException("World context requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(WorldContextSavedData::load,WorldContextSavedData::new,NAME);
    }
    public static WorldContextSavedData load(CompoundTag tag){
        var result=new WorldContextSavedData();
        try{
            if(!tag.contains("Schema",Tag.TAG_INT)||tag.getInt("Schema")!=SCHEMA||!tag.contains("Payload",Tag.TAG_STRING)
                    ||tag.getString("Payload").length()>32_000_000)throw new IllegalArgumentException("unsupported schema");
            JsonObject root=JsonParser.parseString(tag.getString("Payload")).getAsJsonObject();
            for(String key:List.of("revision","sites","routes","journeys","resources","encounters","creditedEntities"))
                if(!root.has(key))throw new IllegalArgumentException("missing "+key);
            result.state=JSON.fromJson(root,State.class);result.state.validate();result.rebuildIndexes(EnumSet.allOf(Index.class));
        }catch(RuntimeException failure){result.state=new State();result.preserved=tag.copy();result.diagnostic=failure.getMessage();}
        return result;
    }
    public boolean writable(){return preserved==null;} public String diagnostic(){return diagnostic;} public long revision(){return state.revision;}
    State snapshot(){return JSON.fromJson(JSON.toJson(state),State.class);}
    boolean commit(MinecraftServer server,State next,Index... changed){
        if(!server.isSameThread())throw new IllegalStateException("World context requires server thread");
        if(!writable())return false;next.validate();
        try{AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat").toFile(),encode(next));
            state=next;rebuildIndexes(changed.length==0?EnumSet.noneOf(Index.class):EnumSet.copyOf(Arrays.asList(changed)));setDirty(false);return true;
        }catch(java.io.IOException failure){com.mojang.logging.LogUtils.getLogger().error("World context transaction was not saved",failure);return false;}
    }
    Collection<Site> sites(){return state.sites.values();} Collection<Route> routes(){return state.routes.values();}
    Collection<Journey> journeys(){return state.journeys.values();} Collection<Encounter> encounters(){return state.encounters.values();}
    List<Journey> activeJourneys(UUID player){return journeysByPlayer.getOrDefault(player,List.of());}
    Optional<Site> site(UUID id){return Optional.ofNullable(state.sites.get(id.toString()));}
    Optional<Route> route(UUID id){return Optional.ofNullable(state.routes.get(id.toString()));}
    Optional<Journey> journey(UUID player,UUID route){return Optional.ofNullable(state.journeys.get(journeyKey(player,route)));}
    Optional<ResourceAccess> resource(UUID site){return Optional.ofNullable(state.resources.get(site.toString()));}
    boolean credited(UUID entity){return state.creditedEntities.contains(entity.toString());}
    List<Site> nearbySites(String dimension,int x,int z,int radius,long cursor,int budget){
        if(budget<1)return List.of();int minX=Math.floorDiv(x-radius,SITE_BUCKET_SIZE),maxX=Math.floorDiv(x+radius,SITE_BUCKET_SIZE);
        int minZ=Math.floorDiv(z-radius,SITE_BUCKET_SIZE),maxZ=Math.floorDiv(z+radius,SITE_BUCKET_SIZE);List<List<Site>> groups=new ArrayList<>();int total=0;
        for(int bx=minX;bx<=maxX;bx++)for(int bz=minZ;bz<=maxZ;bz++){var group=sitesByBucket.get(new SiteBucket(dimension,bx,bz));if(group!=null&&!group.isEmpty()){groups.add(group);total+=group.size();}}
        if(total==0)return List.of();int remaining=Math.floorMod(cursor,total),groupIndex=0,offset=0;
        while(remaining>=groups.get(groupIndex).size()){remaining-=groups.get(groupIndex).size();groupIndex++;}offset=remaining;
        int count=Math.min(budget,total);List<Site> result=new ArrayList<>(count);
        for(int i=0;i<count;i++){var group=groups.get(groupIndex);result.add(group.get(offset++));if(offset==group.size()){offset=0;groupIndex=(groupIndex+1)%groups.size();}}
        return List.copyOf(result);
    }
    /**
     * Returns the deterministic nearest discovered site only when every site in the intersecting
     * buckets fits within the candidate budget. Bucket over-inclusion can conservatively fail closed,
     * but a returned site is never a sampled substitute for an unexamined nearer site.
     */
    Optional<Site> nearestKnownSite(String dimension,int x,int y,int z,int radius,UUID player,int candidateBudget){
        Objects.requireNonNull(dimension);Objects.requireNonNull(player);
        if(radius<0||candidateBudget<1)return Optional.empty();
        int minX=Math.floorDiv(x-radius,SITE_BUCKET_SIZE),maxX=Math.floorDiv(x+radius,SITE_BUCKET_SIZE);
        int minZ=Math.floorDiv(z-radius,SITE_BUCKET_SIZE),maxZ=Math.floorDiv(z+radius,SITE_BUCKET_SIZE);
        List<List<Site>> groups=new ArrayList<>();int candidates=0;
        for(int bx=minX;bx<=maxX;bx++)for(int bz=minZ;bz<=maxZ;bz++){
            var group=sitesByBucket.get(new SiteBucket(dimension,bx,bz));
            if(group==null||group.isEmpty())continue;
            candidates+=group.size();if(candidates>candidateBudget)return Optional.empty();groups.add(group);
        }
        long radiusSquared=(long)radius*radius;
        return groups.stream().flatMap(Collection::stream)
                .filter(site->site.discoverers().contains(player))
                .filter(site->distanceSquared(site,x,y,z)<=radiusSquared)
                .min(Comparator.comparingLong((Site site)->distanceSquared(site,x,y,z)).thenComparing(Site::id));
    }
    int recentEncounters(UUID player,UUID site,String encounter,long now,long window){
        var times=encounterTimes.get(new EncounterKey(player,site,encounter));if(times==null)return 0;
        int from=Collections.binarySearch(times,now-window);if(from<0)from=-from-1;else while(from>0&&times.get(from-1)>=now-window)from--;
        int through=Collections.binarySearch(times,now);if(through<0)through=-through-1;else{while(through+1<times.size()&&times.get(through+1)<=now)through++;through++;}
        return Math.max(0,through-from);
    }
    private void rebuildIndexes(Set<Index> changed){
        if(changed.contains(Index.SITES))rebuildSites();if(changed.contains(Index.JOURNEYS))rebuildJourneys();if(changed.contains(Index.ENCOUNTERS))rebuildEncounters();
    }
    private void rebuildSites(){
        Map<SiteBucket,List<Site>> spatial=new HashMap<>();for(var site:state.sites.values())spatial.computeIfAbsent(new SiteBucket(site.dimension(),
                Math.floorDiv(site.x(),SITE_BUCKET_SIZE),Math.floorDiv(site.z(),SITE_BUCKET_SIZE)),ignored->new ArrayList<>()).add(site);
        spatial.values().forEach(v->v.sort(Comparator.comparing(Site::id)));Map<SiteBucket,List<Site>> frozenSites=new HashMap<>();
        spatial.forEach((key,value)->frozenSites.put(key,List.copyOf(value)));sitesByBucket=Map.copyOf(frozenSites);
    }
    private void rebuildJourneys(){
        Map<UUID,List<Journey>> byPlayer=new HashMap<>();for(var journey:state.journeys.values())if(!journey.complete())
            byPlayer.computeIfAbsent(journey.player(),ignored->new ArrayList<>()).add(journey);
        byPlayer.values().forEach(v->v.sort(Comparator.comparing(Journey::route)));Map<UUID,List<Journey>> frozenJourneys=new HashMap<>();
        byPlayer.forEach((key,value)->frozenJourneys.put(key,List.copyOf(value)));journeysByPlayer=Map.copyOf(frozenJourneys);
    }
    private void rebuildEncounters(){
        Map<EncounterKey,List<Long>> times=new HashMap<>();for(var encounter:state.encounters.values())times.computeIfAbsent(
                new EncounterKey(encounter.player(),encounter.site(),encounter.encounter()),ignored->new ArrayList<>()).add(encounter.completedAt());
        times.values().forEach(Collections::sort);Map<EncounterKey,List<Long>> frozenTimes=new HashMap<>();
        times.forEach((key,value)->frozenTimes.put(key,List.copyOf(value)));encounterTimes=Map.copyOf(frozenTimes);
    }
    static String journeyKey(UUID player,UUID route){return player+":"+route;}
    private static long distanceSquared(Site site,int x,int y,int z){
        long dx=(long)site.x()-x,dy=(long)site.y()-y,dz=(long)site.z()-z;return dx*dx+dy*dy+dz*dz;
    }
    private static CompoundTag encode(State state){var tag=new CompoundTag();tag.putInt("Schema",SCHEMA);tag.putString("Payload",JSON.toJson(state));return tag;}
    @Override public boolean isDirty(){return writable()&&super.isDirty();}
    @Override public CompoundTag save(CompoundTag tag){return writable()?encode(state):preserved.copy();}
    private static void token(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isWhitespace))bad();}
    private static void text(String value,int max){if(value==null||value.length()>max)bad();}
    private static void bad(){throw new IllegalArgumentException("invalid world context record");}
}
