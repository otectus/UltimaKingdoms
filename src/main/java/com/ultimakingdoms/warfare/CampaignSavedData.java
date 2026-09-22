package com.ultimakingdoms.warfare;

import com.google.gson.*;
import com.ultimakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

public final class CampaignSavedData extends SavedData {
    public static final String NAME = "ultima_kingdoms_campaigns";
    static final Gson JSON = new Gson();
    private CampaignState state = new CampaignState();
    private CompoundTag preserved;
    public static CampaignSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Campaigns require server thread");
        return server.overworld().getDataStorage().computeIfAbsent(CampaignSavedData::load, CampaignSavedData::new, NAME);
    }
    public static CampaignSavedData load(CompoundTag tag) {
        var result = new CampaignSavedData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1 || !tag.contains("Payload", Tag.TAG_STRING)
                    || tag.getString("Payload").length() > 32_000_000) throw new IllegalArgumentException("Unsupported campaign schema");
            var json = JsonParser.parseString(tag.getString("Payload")).getAsJsonObject();
            for (String field : new String[]{"revision", "campaigns", "accords", "decisions", "contracts", "receipts", "history"})
                if (!json.has(field)) throw new IllegalArgumentException("Missing campaign field");
            result.state = JSON.fromJson(json, CampaignState.class); result.state.validate();
        } catch (RuntimeException failure) { result.state = new CampaignState(); result.preserved = tag.copy(); }
        return result;
    }
    public boolean writable() { return preserved == null; }
    CampaignState snapshot() {
        var copy = new CampaignState(); copy.revision = state.revision;
        copy.campaigns.putAll(state.campaigns); copy.accords.putAll(state.accords); copy.decisions.putAll(state.decisions);
        copy.contracts.putAll(state.contracts); copy.receipts.putAll(state.receipts); copy.history.addAll(state.history);
        return copy; // All values are validated immutable records.
    }
    boolean commit(MinecraftServer server, CampaignState next) {
        if (!server.isSameThread()) throw new IllegalStateException("Campaigns require server thread");
        if (!writable()) return false; next.validate();
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile(), encode(next));
            state = next; setDirty(false); return true;
        } catch (java.io.IOException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Campaign transaction was not saved", failure); return false;
        }
    }
    private static CompoundTag encode(CampaignState state) { var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", JSON.toJson(state)); return tag; }
    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) { return writable() ? encode(state) : preserved.copy(); }
}
