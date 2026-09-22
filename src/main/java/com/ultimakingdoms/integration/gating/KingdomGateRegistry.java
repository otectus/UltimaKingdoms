package com.ultimakingdoms.integration.gating;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.ultimakingdoms.api.KingdomView;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.gating.KingdomPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Transactional datapack registry for shared, named kingdom predicates. */
public final class KingdomGateRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "ultima_kingdoms/kingdom_gates";
    private static final Set<String> DEFINITION_FIELDS = Set.of(
            "schema", "subject", "include", "exclude", "when_unknown");

    private final AtomicReference<Snapshot> active = new AtomicReference<>(Snapshot.empty());
    private final AtomicReference<Map<ResourceLocation, KingdomPredicate>> pending = new AtomicReference<>();
    private final AtomicLong revisions = new AtomicLong();
    private final Set<ResourceLocation> reportedMissingGates = ConcurrentHashMap.newKeySet();
    private final PreparableReloadListener reloadListener = new Loader();

    public PreparableReloadListener reloadListener() {
        return reloadListener;
    }

    public Optional<KingdomPredicate> get(ResourceLocation id) {
        Optional<KingdomPredicate> result = Optional.ofNullable(active.get().gates().get(id));
        if (result.isEmpty() && reportedMissingGates.add(id)) {
            LOGGER.warn("[Ultima Kingdoms] Referenced kingdom gate {} is not defined; the gate will deny", id);
        }
        return result;
    }

    public long revision() {
        return active.get().revision();
    }

    /** Publishes only a fully prepared snapshot, preserving the prior one after a failed reload. */
    public void commitPending(KingdomsService service) {
        Map<ResourceLocation, KingdomPredicate> staged = pending.getAndSet(null);
        if (staged == null) return;
        Snapshot snapshot = new Snapshot(staged, revisions.incrementAndGet());
        active.set(snapshot);
        reportedMissingGates.clear();
        lintKingdomReferences(service, snapshot);
    }

    public void clear() {
        pending.set(null);
        active.set(Snapshot.empty());
        revisions.set(0L);
        reportedMissingGates.clear();
    }

    private static void lintKingdomReferences(KingdomsService service, Snapshot snapshot) {
        snapshot.gates().forEach((gateId, predicate) -> java.util.stream.Stream
                .concat(predicate.include().stream(), predicate.exclude().stream())
                .distinct()
                .filter(kingdomId -> service.getKingdom(kingdomId).filter(KingdomView::defined).isEmpty())
                .forEach(kingdomId -> LOGGER.warn(
                        "[Ultima Kingdoms] Kingdom gate {} references undefined kingdom {}; it remains loadable",
                        gateId, kingdomId)));
    }

    private final class Loader extends SimplePreparableReloadListener<Map<ResourceLocation, KingdomPredicate>> {
        @Override
        protected Map<ResourceLocation, KingdomPredicate> prepare(ResourceManager resources, ProfilerFiller profiler) {
            pending.set(null);
            try {
                return parse(resources);
            } catch (RuntimeException exception) {
                pending.set(null);
                throw exception;
            }
        }

        @Override
        protected void apply(Map<ResourceLocation, KingdomPredicate> gates, ResourceManager resources,
                             ProfilerFiller profiler) {
            pending.set(gates);
        }
    }

    private static Map<ResourceLocation, KingdomPredicate> parse(ResourceManager resources) {
        Map<ResourceLocation, KingdomPredicate> gates = new LinkedHashMap<>();
        resources.listResources(ROOT, location -> location.getPath().endsWith(".json")).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), gates));
        return Map.copyOf(gates);
    }

    private static void parse(ResourceLocation file, Resource resource,
                              Map<ResourceLocation, KingdomPredicate> gates) {
        ResourceLocation id = logicalId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw error(file, "root must be a JSON object");
            JsonObject json = parsed.getAsJsonObject();
            for (String field : json.keySet()) {
                if (!DEFINITION_FIELDS.contains(field)) throw error(file, "unknown field: " + field);
            }
            if (json.has("schema") && (!json.get("schema").isJsonPrimitive()
                    || !json.getAsJsonPrimitive("schema").isNumber())) {
                throw error(file, "schema must be the number 1");
            }
            double schemaNumber = json.has("schema") ? json.get("schema").getAsDouble() : 1D;
            if (!Double.isFinite(schemaNumber) || schemaNumber != Math.rint(schemaNumber)) {
                throw error(file, "schema must be an integer");
            }
            int schema = (int) schemaNumber;
            if (schema != 1) throw error(file, "unsupported schema " + schema);
            JsonObject predicateJson = json.deepCopy();
            predicateJson.remove("schema");
            KingdomPredicate previous = gates.putIfAbsent(id, KingdomPredicate.fromJson(predicateJson));
            if (previous != null) throw error(file, "duplicate logical gate id " + id);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed reading kingdom gate " + file, exception);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException
                    && exception.getMessage() != null
                    && exception.getMessage().startsWith(file + ": ")) {
                throw exception;
            }
            throw error(file, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private static ResourceLocation logicalId(ResourceLocation file) {
        String path = file.getPath();
        String prefix = ROOT + "/";
        if (!path.startsWith(prefix) || !path.endsWith(".json")) {
            throw error(file, "path is outside " + ROOT);
        }
        String logicalPath = path.substring(prefix.length(), path.length() - ".json".length());
        if (logicalPath.isEmpty()) throw error(file, "empty logical gate id");
        return new ResourceLocation(file.getNamespace(), logicalPath);
    }

    private static IllegalArgumentException error(ResourceLocation file, String message) {
        return new IllegalArgumentException(file + ": " + message);
    }

    private record Snapshot(Map<ResourceLocation, KingdomPredicate> gates, long revision) {
        private Snapshot {
            gates = Map.copyOf(gates);
        }

        private static Snapshot empty() {
            return new Snapshot(Map.of(), 0L);
        }
    }
}
