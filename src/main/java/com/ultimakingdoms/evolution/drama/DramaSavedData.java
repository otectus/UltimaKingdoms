package com.ultimakingdoms.evolution.drama;

import com.google.gson.*;
import com.ultimakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Independent save domain so unsupported drama data stays preserved read-only. */
public final class DramaSavedData extends SavedData {
    public static final String NAME = "ultima_kingdoms_evolution_drama";
    static final Gson JSON = new Gson();
    private DramaState state = new DramaState();
    private CompoundTag preserved;
    public static DramaSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Drama requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(DramaSavedData::load, DramaSavedData::new, NAME);
    }
    public static DramaSavedData load(CompoundTag tag) {
        var data = new DramaSavedData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1 || !tag.contains("Payload", Tag.TAG_STRING)
                    || tag.getString("Payload").length() > 8_000_000) throw new IllegalArgumentException("Unsupported drama payload");
            var json = JsonParser.parseString(tag.getString("Payload")).getAsJsonObject();
            for (String field : new String[]{"revision", "cursor", "dramas", "receipts"}) if (!json.has(field)) throw new IllegalArgumentException("Missing drama field");
            data.state = JSON.fromJson(json, DramaState.class); data.state.validate();
        } catch (RuntimeException failure) { data.state = new DramaState(); data.preserved = tag.copy(); }
        return data;
    }
    public boolean writable() { return preserved == null; }
    DramaState snapshot() { return JSON.fromJson(JSON.toJson(state), DramaState.class); }
    boolean commit(MinecraftServer server, DramaState next) {
        if (!server.isSameThread()) throw new IllegalStateException("Drama requires server thread");
        if (!writable()) return false; next.validate();
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), encode(next));
            state = next; setDirty(false); return true;
        } catch (java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Drama transaction refused: save failed", failure); return false; }
    }
    static CompoundTag encode(DramaState state) { var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", JSON.toJson(state)); return tag; }
    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) { return writable() ? encode(state) : preserved.copy(); }
}
