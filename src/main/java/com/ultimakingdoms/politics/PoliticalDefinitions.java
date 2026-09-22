package com.ultimakingdoms.politics;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.ultimakingdoms.api.politics.Politics.Definition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** One atomic snapshot across all six political definition families. */
public final class PoliticalDefinitions extends SimplePreparableReloadListener<Map<String, Definition>> {
    private static final Map<String, String> FAMILIES = Map.of("governments", "government", "offices", "office",
            "agreements", "agreement", "petitions", "petition", "honors", "honor", "institution_charters", "institution");
    private volatile Map<String, Definition> definitions = Map.of();
    private volatile Map<String, Definition> pending;
    @Override protected Map<String, Definition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        pending = null;
        Map<String, Definition> next = new LinkedHashMap<>();
        manager.listResources("ultima_kingdoms", path -> path.getPath().endsWith(".json")).forEach((path, resource) -> {
            String[] parts = path.getPath().substring("ultima_kingdoms/".length()).split("/", 2);
            if (parts.length != 2 || !FAMILIES.containsKey(parts[0])) return;
            try (var reader = resource.openAsReader()) {
                Definition definition = PoliticalSavedData.JSON.fromJson(reader, Definition.class);
                if (!FAMILIES.get(parts[0]).equals(definition.kind())) throw new IllegalArgumentException("Wrong family: " + path);
                String key = path.getNamespace() + ":" + parts[1].substring(0, parts[1].length() - 5);
                if (next.putIfAbsent(key, definition) != null) throw new IllegalArgumentException("Duplicate political ID " + key);
            } catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot read political definition " + path, failure); }
        });
        next.forEach((id, value) -> {
            value.offices().forEach(office -> require(next, office, "office"));
            value.requires().forEach(required -> require(next, required, "agreement"));
        });
        return Map.copyOf(next);
    }
    @Override protected void apply(Map<String, Definition> next, ResourceManager manager, ProfilerFiller profiler) { pending = next; }
    public void commitPending() { if (pending != null) { definitions = pending; pending = null; } }
    public void clear() { definitions = Map.of(); pending = null; }
    public Definition get(String id, String kind) { return require(definitions, id, kind); }
    public boolean available(String id) { return definitions.containsKey(id); }
    public Map<String, Definition> snapshot() { return definitions; }
    private static Definition require(Map<String, Definition> values, String id, String kind) {
        Definition value = values.get(id);
        if (ResourceLocation.tryParse(id) == null || value == null || !kind.equals(value.kind()))
            throw new IllegalArgumentException("Political definition unavailable: " + id);
        return value;
    }
}
