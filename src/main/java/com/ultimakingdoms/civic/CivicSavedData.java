package com.ultimakingdoms.civic;

import com.google.gson.Gson;
import com.ultimakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;

/** Sparse appointments and introduction receipts. Provider building/government state is never copied. */
final class CivicSavedData extends SavedData {
    static final String NAME="ultima_kingdoms_civic_network";
    private static final Gson JSON=new Gson();
    record Site(UUID settlement,String dimension,int villageId,int buildingId,String family,String type,int x,int y,int z) { }
    record Chapter(UUID id,String organization,UUID institution,Site site,UUID appointedBy,long revision) {
        Chapter(UUID id,String organization,UUID institution,UUID appointedBy,long revision) { this(id,organization,institution,null,appointedBy,revision); }
    }
    record Seed(UUID npc,String organization,UUID settlement,String dimension,Integer village,UUID receipt,long revision) { }
    record Contact(UUID npc,UUID chapter,UUID appointedBy,long revision) { }
    record Introduction(UUID player,String organization,UUID source,UUID target,UUID settlement,long createdAt) { }
    static final class State {
        long revision;
        Map<UUID,Chapter> chapters=new LinkedHashMap<>();
        Map<UUID,Contact> contacts=new LinkedHashMap<>();
        Map<UUID,Seed> seeds=new LinkedHashMap<>();
        Set<UUID> dismissed=new LinkedHashSet<>();
        Map<String,Introduction> introductions=new LinkedHashMap<>();
        void validate() {
            if(revision<0||chapters==null||contacts==null||seeds==null||dismissed==null||dismissed.contains(null)||dismissed.size()>16384||introductions==null||chapters.size()>4096||contacts.size()>16384||seeds.size()>16384||introductions.size()>100000)
                throw new IllegalArgumentException("Civic state capacity or schema invalid");
            chapters.forEach((id,c)->{ if(c==null||!id.equals(c.id)||(c.institution==null)==(c.site==null)||c.appointedBy==null||c.revision<0||c.revision>revision||
                    c.organization==null||c.organization.length()>128||net.minecraft.resources.ResourceLocation.tryParse(c.organization)==null) throw new IllegalArgumentException("Invalid chapter"); });
            chapters.values().stream().filter(c->c.site!=null).forEach(c->{var b=c.site;
                if(b.settlement==null||b.dimension==null||net.minecraft.resources.ResourceLocation.tryParse(b.dimension)==null||b.villageId<0||b.buildingId<0||b.family==null||b.family.length()>64||b.type==null||b.type.length()>64)throw new IllegalArgumentException("Invalid civic site"); });
            seeds.forEach((id,seed)->{
                if(seed!=null&&((seed.dimension==null)!=(seed.village==null)||seed.village!=null&&seed.village<0||seed.dimension!=null&&(seed.dimension.length()>128||net.minecraft.resources.ResourceLocation.tryParse(seed.dimension)==null)))throw new IllegalArgumentException("Invalid pending community reference");
                if(seed==null||!id.equals(seed.npc)||seed.receipt==null||(seed.settlement==null&&(seed.dimension==null||seed.village==null||seed.village<0))||seed.organization==null||net.minecraft.resources.ResourceLocation.tryParse(seed.organization)==null||seed.revision<0||seed.revision>revision)throw new IllegalArgumentException("Invalid authored role");});
            contacts.forEach((id,c)->{ if(c==null||!id.equals(c.npc)||c.appointedBy==null||!chapters.containsKey(c.chapter)||c.revision<0||c.revision>revision) throw new IllegalArgumentException("Invalid contact"); });
            introductions.forEach((key,i)->{ if(i==null||i.player==null||i.source==null||i.target==null||i.settlement==null||i.organization==null||i.createdAt<0||
                    !key.equals(i.player+"|"+i.organization)) throw new IllegalArgumentException("Invalid introduction"); });
        }
    }
    private State state=new State();
    private CompoundTag preserved;
    private String diagnostic="";
    static CivicSavedData get(MinecraftServer server) {
        if(!server.isSameThread()) throw new IllegalStateException("Civic network requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(CivicSavedData::load,CivicSavedData::new,NAME);
    }
    static CivicSavedData load(CompoundTag tag) {
        var data=new CivicSavedData();
        try {
            if(!tag.contains("Schema",Tag.TAG_INT)||tag.getInt("Schema")!=1||!tag.contains("Payload",Tag.TAG_STRING)) throw new IllegalArgumentException("Unsupported civic schema");
            data.state=JSON.fromJson(tag.getString("Payload"),State.class); Objects.requireNonNull(data.state).validate();
        } catch(RuntimeException failure) { data.state=new State();data.preserved=tag.copy();data.diagnostic="Civic network is read-only: "+failure.getMessage(); }
        return data;
    }
    State state() { return state; }
    boolean writable() { return preserved==null; }
    String diagnostic() { return diagnostic; }
    State copy() { if(!writable()) throw new IllegalStateException(diagnostic); return JSON.fromJson(JSON.toJson(state),State.class); }
    boolean commit(MinecraftServer server,State candidate) {
        if(!writable()) return false; candidate.validate();
        try {
            CompoundTag tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Payload",JSON.toJson(candidate));
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat").toFile(),tag);
            state=candidate;setDirty(false);return true;
        } catch(java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Civic transaction was not saved",failure);return false; }
    }
    @Override public boolean isDirty() { return writable()&&super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) {
        if(!writable()) return preserved.copy();tag.putInt("Schema",1);tag.putString("Payload",JSON.toJson(state));return tag;
    }
}
