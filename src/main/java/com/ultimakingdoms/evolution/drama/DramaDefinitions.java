package com.ultimakingdoms.evolution.drama;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Atomically reloaded, bounded authored drama catalog. Active dramas retain frozen terms. */
public final class DramaDefinitions extends SimplePreparableReloadListener<Map<String, DramaState.Template>> {
    public static final DramaDefinitions INSTANCE = new DramaDefinitions();
    private volatile Map<String, DramaState.Template> current = Map.of();
    private volatile Map<String, DramaState.Template> pending;
    @Override protected Map<String, DramaState.Template> prepare(ResourceManager manager, ProfilerFiller profiler) {
        pending = null; var next = new TreeMap<String, DramaState.Template>();
        manager.listResources("evolution/drama", path -> path.getPath().endsWith(".json")).forEach((path, resource) -> {
            try (var reader = resource.openAsReader()) {
                String id = path.getNamespace() + ":" + path.getPath().substring("evolution/drama/".length(), path.getPath().length() - 5);
                if (next.size() >= 32 || ResourceLocation.tryParse(id) == null) throw new IllegalArgumentException("Invalid drama catalog");
                var parsed = Objects.requireNonNull(DramaSavedData.JSON.fromJson(reader, DramaState.Template.class));
                // Reconstruct to invoke the record's validation even if a Gson version bypasses constructors.
                parsed = new DramaState.Template(parsed.title(), parsed.kind(), parsed.outcomes(), parsed.goal(), parsed.objective(),
                        parsed.durationTicks(), parsed.maxParticipants(), parsed.operationBudget(), parsed.requiresOccupation(),
                        parsed.organizationTemplate(), parsed.organizationName());
                if (next.put(id, parsed) != null) throw new IllegalArgumentException("Duplicate drama " + id);
            } catch (java.io.IOException | RuntimeException failure) { throw new IllegalArgumentException("Cannot read drama " + path, failure); }
        });
        return Map.copyOf(next);
    }
    @Override protected void apply(Map<String, DramaState.Template> next, ResourceManager manager, ProfilerFiller profiler) { pending = next; }
    public void commitPending() { if (pending != null) { current = pending; pending = null; } }
    public Map<String, DramaState.Template> snapshot() { return current; }
    public void clear() { current = Map.of(); pending = null; }
}
