package com.ultimakingdoms.evolution;

import com.google.gson.*;
import com.ultimakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

public final class ProtectionSavedData extends SavedData {
    public static final String NAME = "ultima_kingdoms_protection";
    static final Gson JSON = new Gson();
    private ProtectionState state = new ProtectionState();
    private CompoundTag preserved;
    public static ProtectionSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Protection requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(ProtectionSavedData::load, ProtectionSavedData::new, NAME);
    }
    public static ProtectionSavedData load(CompoundTag tag) {
        var data = new ProtectionSavedData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1 || !tag.contains("Payload", Tag.TAG_STRING)
                    || tag.getString("Payload").length() > 16_000_000) throw new IllegalArgumentException("Unsupported protection payload");
            var json = JsonParser.parseString(tag.getString("Payload")).getAsJsonObject();
            for (String field : new String[]{"revision", "clock", "pacts", "obligations", "receipts"})
                if (!json.has(field)) throw new IllegalArgumentException("Missing protection field");
            data.state = JSON.fromJson(json, ProtectionState.class); data.state.validate();
        } catch (RuntimeException failure) { data.state = new ProtectionState(); data.preserved = tag.copy(); }
        return data;
    }
    public boolean writable() { return preserved == null; }
    ProtectionState snapshot() { return JSON.fromJson(JSON.toJson(state), ProtectionState.class); }
    boolean commit(MinecraftServer server, ProtectionState next) {
        if (!server.isSameThread()) throw new IllegalStateException("Protection requires server thread");
        if (!writable()) return false; next.validate();
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), encode(next));
            state = next; setDirty(false); return true;
        } catch (java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Protection transaction save failed", failure); return false; }
    }
    private static CompoundTag encode(ProtectionState state) { var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", JSON.toJson(state)); return tag; }
    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) { return writable() ? encode(state) : preserved.copy(); }
}
