package com.ultimakingdoms.worldcontext;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;

/** Data-pack selectors. Invalid reloads retain the last complete snapshot. */
public final class WorldContextDefinitions extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().create();
    private static volatile Snapshot current = new Snapshot(Map.of(), Map.of());
    public WorldContextDefinitions() { super(GSON, "warfare/world"); }

    public record EncounterRule(String role, int repeatBudget, int contributionWindow) { }
    public record Snapshot(Map<ResourceLocation, String> structures,
                           Map<ResourceLocation, EncounterRule> encounters) {
        public Snapshot { structures = Map.copyOf(structures); encounters = Map.copyOf(encounters); }
    }
    public static Snapshot get() { return current; }

    @Override protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                                   ProfilerFiller profiler) {
        Map<ResourceLocation, String> structures = new HashMap<>();
        Map<ResourceLocation, EncounterRule> encounters = new HashMap<>();
        try {
            for (var source : resources.entrySet()) {
                JsonObject root = source.getValue().getAsJsonObject();
                if (root.has("structures")) for (JsonElement value : root.getAsJsonArray("structures")) {
                    JsonObject item = value.getAsJsonObject();
                    ResourceLocation id = requiredId(item, "id");
                    String role = token(item, "role", 48);
                    if (structures.putIfAbsent(id, role) != null) throw new JsonParseException("duplicate structure " + id);
                }
                if (root.has("encounters")) for (JsonElement value : root.getAsJsonArray("encounters")) {
                    JsonObject item = value.getAsJsonObject(); ResourceLocation id = requiredId(item, "id");
                    EncounterRule rule = new EncounterRule(token(item, "role", 48),
                            bounded(item, "repeat_budget", 1, 32), bounded(item, "contribution_window", 20, 12000));
                    if (encounters.putIfAbsent(id, rule) != null) throw new JsonParseException("duplicate encounter " + id);
                }
            }
            current = new Snapshot(structures, encounters);
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().error("World context definitions rejected; retaining prior snapshot", failure);
        }
    }
    private static ResourceLocation requiredId(JsonObject object, String key) {
        ResourceLocation id = ResourceLocation.tryParse(object.has(key) ? object.get(key).getAsString() : "");
        if (id == null) throw new JsonParseException("invalid " + key); return id;
    }
    private static String token(JsonObject object, String key, int max) {
        String value = object.has(key) ? object.get(key).getAsString() : "";
        if (!value.matches("[a-z0-9_.:-]{1," + max + "}")) throw new JsonParseException("invalid " + key); return value;
    }
    private static int bounded(JsonObject object, String key, int min, int max) {
        int value = object.has(key) ? object.get(key).getAsInt() : min;
        if (value < min || value > max) throw new JsonParseException("invalid " + key); return value;
    }
}
