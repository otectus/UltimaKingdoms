package com.ultimakingdoms.warfare.mobilization;

import com.google.gson.*;
import com.ultimakingdoms.compat.recruits.RecruitsMobilization.Orders;
import com.ultimakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.util.*;

/** Durable leases are written before native orders. Terminal records are dedupe tombstones. */
public final class MobilizationSavedData extends SavedData {
    public static final String NAME="ultima_kingdoms_mobilization";static final int SCHEMA=1,LIMIT=8192;
    static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    public enum Phase { PREPARED, ACTIVE_PENDING_NATIVE_SAVE, DURABLE_ACTIVE, RESTORE_PENDING, RESTORE_PENDING_NATIVE_SAVE, RESTORED, DIVERGED, SUSPENDED }
    public record Lease(UUID id,UUID unit,UUID actor,String faction,String kingdom,MobilizationDoctrine doctrine,
                        Orders before,Orders applied,long createdAt,long expiresAt,Phase phase,String detail){
        public Lease{Objects.requireNonNull(id);Objects.requireNonNull(unit);Objects.requireNonNull(actor);token(faction);token(kingdom);Objects.requireNonNull(doctrine);
            Objects.requireNonNull(before);Objects.requireNonNull(applied);Objects.requireNonNull(phase);text(detail);if(!before.unit().equals(unit)||!applied.unit().equals(unit)
                    ||!actor.equals(before.owner())||!actor.equals(applied.owner())||!Objects.equals(before.group(),applied.group())
                    ||createdAt<0||expiresAt<=createdAt)throw new IllegalArgumentException("invalid mobilization lease");}
        Lease phase(Phase next,String reason){return new Lease(id,unit,actor,faction,kingdom,doctrine,before,applied,createdAt,expiresAt,next,reason);}
        boolean terminal(){return phase==Phase.RESTORED;}
    }
    static final class State {long revision;Map<String,Lease> leases=new HashMap<>();void validate(){if(revision<0||leases==null||leases.size()>LIMIT)bad();
        Set<UUID> active=new HashSet<>();leases.forEach((k,v)->{if(v==null||!k.equals(v.id().toString()))bad();if(!v.terminal()&&!active.add(v.unit()))bad();});}}
    private State state=new State();private CompoundTag preserved;private String diagnostic="";
    public static MobilizationSavedData get(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Mobilization requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(MobilizationSavedData::load,MobilizationSavedData::new,NAME);}
    public static MobilizationSavedData load(CompoundTag tag){var result=new MobilizationSavedData();try{
        if(!tag.contains("Schema",Tag.TAG_INT)||tag.getInt("Schema")!=SCHEMA||!tag.contains("Payload",Tag.TAG_STRING)||tag.getString("Payload").length()>16_000_000)bad();
        JsonObject root=JsonParser.parseString(tag.getString("Payload")).getAsJsonObject();if(!root.has("revision")||!root.has("leases"))bad();
        result.state=JSON.fromJson(root,State.class);result.state.validate();}catch(RuntimeException failure){result.state=new State();result.preserved=tag.copy();result.diagnostic=failure.getMessage();}return result;}
    public boolean writable(){return preserved==null;}public String diagnostic(){return diagnostic;}public long revision(){return state.revision;}
    State snapshot(){return JSON.fromJson(JSON.toJson(state),State.class);}Collection<Lease> leases(){return state.leases.values();}
    Optional<Lease> lease(UUID id){return Optional.ofNullable(state.leases.get(id.toString()));}
    Optional<Lease> restorableLease(UUID id){return lease(id).filter(l->!l.terminal());}
    Optional<Lease> activeUnit(UUID unit){return state.leases.values().stream().filter(l->l.unit().equals(unit)&&!l.terminal()).findFirst();}
    public boolean transferBlocked(UUID unit){return !writable()||activeUnit(unit).isPresent();}
    boolean commit(MinecraftServer server,State next){if(!server.isSameThread())throw new IllegalStateException("Mobilization requires server thread");if(!writable())return false;next.validate();
        try{AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat").toFile(),encode(next));state=next;setDirty(false);return true;}
        catch(java.io.IOException failure){com.mojang.logging.LogUtils.getLogger().error("Mobilization lease was not saved",failure);return false;}}
    private static CompoundTag encode(State state){var tag=new CompoundTag();tag.putInt("Schema",SCHEMA);tag.putString("Payload",JSON.toJson(state));return tag;}
    @Override public boolean isDirty(){return writable()&&super.isDirty();}@Override public CompoundTag save(CompoundTag tag){return writable()?encode(state):preserved.copy();}
    private static void token(String value){if(value==null||value.isBlank()||value.length()>128||value.chars().anyMatch(Character::isISOControl))bad();}
    private static void text(String value){if(value==null||value.length()>256)bad();}private static void bad(){throw new IllegalArgumentException("invalid mobilization data");}
}
