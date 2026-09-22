package com.ultimakingdoms.civic;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Explicit authored sponsor roles. Ordinary villagers are never enrolled by profession or chunk load. */
public final class CivicDefinitions extends SimpleJsonResourceReloadListener {
    public static final CivicDefinitions INSTANCE=new CivicDefinitions();
    public record Rules(ResourceLocation organization,Set<ResourceLocation> sponsorQuests,Set<String> buildingFamilies,Set<ResourceLocation> commissions) {
        public Rules { sponsorQuests=Set.copyOf(sponsorQuests);buildingFamilies=Set.copyOf(buildingFamilies);commissions=Set.copyOf(commissions); }
    }
    private volatile Map<ResourceLocation,Rules> committed=Map.of(),pending;
    private long revision;
    private CivicDefinitions() { super(new Gson(),"ultima_factions/civic_network"); }
    @Override protected void apply(Map<ResourceLocation,JsonElement> resources,ResourceManager manager,ProfilerFiller profiler) {
        try {
            if(resources.size()>128)throw new IllegalArgumentException("Too many civic definitions");
            Map<ResourceLocation,Rules> next=new LinkedHashMap<>();
            for(var entry:resources.entrySet()) {
                var json=entry.getValue().getAsJsonObject();
                if(!Set.of("schema","organization","sponsor_quests","building_families","commissions").containsAll(json.keySet())||!json.get("schema").isJsonPrimitive()||!json.get("schema").getAsJsonPrimitive().isNumber()||!json.get("schema").getAsString().equals("1"))throw new IllegalArgumentException("Invalid civic fields/schema");
                ResourceLocation organization=id(json.get("organization").getAsString());
                Set<ResourceLocation> sponsor=resources(json.getAsJsonArray("sponsor_quests"),128);
                Set<ResourceLocation> commissions=resources(json.getAsJsonArray("commissions"),32);
                Set<String> families=new LinkedHashSet<>();
                for(JsonElement value:json.getAsJsonArray("building_families")) {
                    String family=value.getAsString();if(!family.matches("[a-z0-9_]{1,64}")||!families.add(family))throw new IllegalArgumentException("Invalid civic building family");
                }
                if(families.isEmpty()||families.size()>16||next.putIfAbsent(organization,new Rules(organization,sponsor,families,commissions))!=null)throw new IllegalArgumentException("Invalid/duplicate civic organization");
            }
            pending=Map.copyOf(next);
        } catch(RuntimeException failure) { pending=null;com.mojang.logging.LogUtils.getLogger().error("Rejected civic definitions; retaining last committed set",failure); }
    }
    private static Set<ResourceLocation> resources(JsonArray array,int max) {
        if(array==null||array.size()>max)throw new IllegalArgumentException("Invalid civic ID list");
        Set<ResourceLocation> ids=new LinkedHashSet<>();for(JsonElement value:array)if(!ids.add(id(value.getAsString())))throw new IllegalArgumentException("Duplicate civic ID");return ids;
    }
    private static ResourceLocation id(String text) { var id=ResourceLocation.tryParse(text);if(text.length()>128||id==null||!id.toString().equals(text))throw new IllegalArgumentException("Invalid civic ID");return id; }
    public void commitPending() { if(pending!=null){committed=pending;pending=null;revision++;} }
    public Optional<Rules> get(ResourceLocation organization) { return Optional.ofNullable(committed.get(organization)); }
    public long revision() { return revision; }
    public void clear() { committed=Map.of();pending=null;revision=0; }
}
