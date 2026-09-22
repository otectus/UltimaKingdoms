package com.ultimakingdoms.evolution;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Atomic authored scenario catalog. Existing scenarios keep their frozen terms. */
public final class EvolutionDefinitions extends SimplePreparableReloadListener<Map<String, EvolutionState.Template>> {
    public static final EvolutionDefinitions INSTANCE = new EvolutionDefinitions();
    private volatile Map<String, EvolutionState.Template> current = Map.of();
    private volatile Map<String, EvolutionState.Template> pending;
    @Override protected Map<String, EvolutionState.Template> prepare(ResourceManager manager, ProfilerFiller profiler) {
        pending = null; var next = new TreeMap<String, EvolutionState.Template>();
        manager.listResources("evolution/scenarios", path -> path.getPath().endsWith(".json")).forEach((path, resource) -> {
            try (var reader = resource.openAsReader()) {
                String id = path.getNamespace() + ":" + path.getPath().substring("evolution/scenarios/".length(), path.getPath().length() - 5);
                if (next.size() >= 64 || ResourceLocation.tryParse(id) == null) throw new IllegalArgumentException("Invalid scenario catalog");
                next.put(id, Objects.requireNonNull(EvolutionSavedData.JSON.fromJson(reader, EvolutionState.Template.class)));
            } catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot read scenario " + path, failure); }
        });
        return Map.copyOf(next);
    }
    @Override protected void apply(Map<String, EvolutionState.Template> next, ResourceManager manager, ProfilerFiller profiler) { pending = next; }
    public void commitPending() { if (pending != null) { current = pending; pending = null; } }
    public Map<String, EvolutionState.Template> snapshot() { return current; }
    public void clear() { current = Map.of(); pending = null; }
}
