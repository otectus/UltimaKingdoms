package com.ultimakingdoms.evolution;

import com.google.gson.*;
import com.ultimakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Synchronous durable intent fence; unsupported data is preserved without writes. */
public final class EvolutionSavedData extends SavedData {
    public static final String NAME = "ultima_kingdoms_evolution";
    static final Gson JSON = new Gson();
    private EvolutionState state = new EvolutionState();
    private CompoundTag preserved;
    public static EvolutionSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(EvolutionSavedData::load, EvolutionSavedData::new, NAME);
    }
    public static EvolutionSavedData load(CompoundTag tag) {
        var data = new EvolutionSavedData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1
                    || !tag.contains("Payload", Tag.TAG_STRING) || tag.getString("Payload").length() > 16_000_000)
                throw new IllegalArgumentException("Unsupported evolution payload");
            var json = JsonParser.parseString(tag.getString("Payload")).getAsJsonObject();
            for (String field : new String[]{"revision", "clock", "enabled", "drama", "nextEvaluation", "lastEvaluation", "cursor", "eligible", "activeRegions",
                    "scenarios", "cooldowns", "consumed", "digest", "subscriptions"})
                if (!json.has(field)) throw new IllegalArgumentException("Missing evolution field");
            data.state = JSON.fromJson(json, EvolutionState.class); data.state.validate();
        } catch (RuntimeException failure) { data.state = new EvolutionState(); data.preserved = tag.copy(); }
        return data;
    }
    public boolean writable() { return preserved == null; }
    boolean evaluationDue(long gameTime) { return writable() && state.enabled && Math.max(gameTime, state.clock) >= state.nextEvaluation; }
    EvolutionState snapshot() { return JSON.fromJson(JSON.toJson(state), EvolutionState.class); }
    boolean commit(MinecraftServer server, EvolutionState next) {
        if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread");
        if (!writable()) return false;
        next.validate();
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), encode(next));
            state = next; setDirty(false); return true;
        } catch (java.io.IOException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Evolution transaction refused: save failed", failure); return false;
        }
    }
    private static CompoundTag encode(EvolutionState state) {
        String payload = JSON.toJson(state); if (payload.length() > 16_000_000) throw new IllegalArgumentException("Evolution storage budget reached");
        var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", payload); return tag;
    }
    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) { return writable() ? encode(state) : preserved.copy(); }
}
