package com.ultimakingdoms.factions.organization;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultimakingdoms.api.factions.organization.OrganizationRankView;
import com.ultimakingdoms.api.factions.organization.OrganizationDeedRule;
import com.ultimakingdoms.api.factions.organization.OrganizationServiceRule;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Transactional strict-schema loader for data/&lt;namespace&gt;/ultima_factions/factions/*.json. */
public final class OrganizationDefinitions {
    private static final String ROOT = "ultima_factions/factions";
    private static final Set<String> KINDS = Set.of("guild", "order", "institution", "civic_group");
    private final AtomicReference<Snapshot> active = new AtomicReference<>(Snapshot.empty());
    private final AtomicReference<Map<ResourceLocation, OrganizationDefinition>> pending = new AtomicReference<>();
    private final AtomicLong generations = new AtomicLong();
    private final PreparableReloadListener listener = new Loader();

    public PreparableReloadListener reloadListener() {
        return listener;
    }

    public void commitPending() {
        Map<ResourceLocation, OrganizationDefinition> staged = pending.getAndSet(null);
        if (staged != null) active.set(new Snapshot(generations.incrementAndGet(), staged));
    }

    public void clear() {
        pending.set(null);
        active.set(Snapshot.empty());
        generations.set(0L);
    }

    Snapshot snapshot() {
        return active.get();
    }

    private final class Loader extends SimplePreparableReloadListener<Map<ResourceLocation, OrganizationDefinition>> {
        @Override
        protected Map<ResourceLocation, OrganizationDefinition> prepare(ResourceManager resources,
                                                                         ProfilerFiller profiler) {
            pending.set(null);
            Map<ResourceLocation, OrganizationDefinition> parsed = new LinkedHashMap<>();
            resources.listResources(ROOT, location -> location.getPath().endsWith(".json")).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        ResourceLocation file = entry.getKey();
                        String path = file.getPath();
                        String prefix = ROOT + "/";
                        if (!path.startsWith(prefix) || path.length() <= prefix.length() + 5) return;
                        String relative = path.substring(prefix.length(), path.length() - 5);
                        ResourceLocation id = ResourceLocation.tryParse(file.getNamespace() + ":" + relative);
                        if (id == null) throw error(file, "file path does not form an organization id");
                        try (Reader reader = entry.getValue().openAsReader()) {
                            JsonElement element = JsonParser.parseReader(reader);
                            if (!element.isJsonObject()) throw error(file, "root must be an object");
                            OrganizationDefinition definition = parseDefinition(file, id, element.getAsJsonObject());
                            if (parsed.putIfAbsent(id, definition) != null) {
                                throw error(file, "duplicate organization id " + id);
                            }
                        } catch (IOException exception) {
                            throw error(file, "cannot read definition", exception);
                        }
                    });
            if(parsed.size()>128)throw new IllegalArgumentException("At most 128 organization definitions are supported");
            validateCrossReferences(parsed);
            return Map.copyOf(parsed);
        }

        @Override
        protected void apply(Map<ResourceLocation, OrganizationDefinition> definitions,
                             ResourceManager resources, ProfilerFiller profiler) {
            pending.set(definitions);
        }
    }

    static OrganizationDefinition parseDefinition(ResourceLocation file, ResourceLocation expectedId,
                                                  JsonObject json) {
        fields(file, json, Set.of("schema", "id", "kind", "military", "name_key", "description_key",
                "membership", "ranks", "services", "deeds"));
        if (integer(file, json, "schema") != 1) throw error(file, "unsupported schema; expected 1");
        ResourceLocation declared = resource(file, string(file, json, "id"), "id");
        if (!expectedId.equals(declared)) throw error(file, "declared id does not match file id " + expectedId);
        String kind = string(file, json, "kind");
        if (!KINDS.contains(kind)) throw error(file, "unsupported organization kind " + kind);
        boolean military = bool(file, json, "military");
        if (military) throw error(file, "military organizations remain provider-owned and are unsupported here");
        String nameKey = bounded(file, string(file, json, "name_key"), "name_key", 128);
        String descriptionKey = bounded(file, string(file, json, "description_key"), "description_key", 160);

        JsonObject membership = object(file, json, "membership");
        fields(file, membership, Set.of("exclusive_group", "conflicts"));
        Optional<ResourceLocation> exclusiveGroup = optionalResource(file, membership, "exclusive_group");
        Set<ResourceLocation> conflicts = resources(file, array(file, membership, "conflicts"), "conflicts", 64);
        if (conflicts.contains(expectedId)) throw error(file, "organization cannot conflict with itself");

        JsonArray rankArray = array(file, json, "ranks");
        if (rankArray.size() == 0 || rankArray.size() > 32) throw error(file, "ranks must contain 1..32 entries");
        List<OrganizationRankView> ranks = new ArrayList<>();
        Set<ResourceLocation> rankIds = new LinkedHashSet<>();
        long lastThreshold = Long.MIN_VALUE;
        for (JsonElement element : rankArray) {
            if (!element.isJsonObject()) throw error(file, "rank entries must be objects");
            JsonObject rank = element.getAsJsonObject();
            fields(file, rank, Set.of("id", "minimum_standing", "permissions"));
            ResourceLocation rankId = resource(file, string(file, rank, "id"), "ranks.id");
            long threshold = longValue(file, rank, "minimum_standing");
            if (!rankIds.add(rankId)) throw error(file, "duplicate rank " + rankId);
            if (!ranks.isEmpty() && threshold <= lastThreshold) {
                throw error(file, "rank minimum_standing values must be strictly increasing");
            }
            Set<ResourceLocation> permissions = resources(file, array(file, rank, "permissions"),
                    "ranks.permissions", 64);
            ranks.add(new OrganizationRankView(rankId, threshold, permissions));
            lastThreshold = threshold;
        }

        JsonArray serviceArray = array(file, json, "services");
        if (serviceArray.size() > 64) throw error(file, "services exceeds 64 entries");
        List<OrganizationServiceRule> services = new ArrayList<>();
        Set<ResourceLocation> serviceIds = new LinkedHashSet<>();
        Map<ResourceLocation, Integer> rankIndexes = new LinkedHashMap<>();
        for (int index = 0; index < ranks.size(); index++) rankIndexes.put(ranks.get(index).id(), index);
        for (JsonElement element : serviceArray) {
            if (!element.isJsonObject()) throw error(file, "service entries must be objects");
            JsonObject service = element.getAsJsonObject();
            fields(file, service, Set.of("permission", "minimum_rank", "minimum_standing", "required_deeds", "neutral_minimum_standing", "neutral_required_deeds"));
            ResourceLocation permission = resource(file, string(file, service, "permission"), "services.permission");
            ResourceLocation minimumRank = resource(file, string(file, service, "minimum_rank"), "services.minimum_rank");
            long minimumStanding = longValue(file, service, "minimum_standing");
            int requiredDeeds = integer(file, service, "required_deeds");
            if (!serviceIds.add(permission)) throw error(file, "duplicate service permission " + permission);
            Integer minimumIndex = rankIndexes.get(minimumRank);
            if (minimumIndex == null) throw error(file, "service references unknown rank " + minimumRank);
            if (requiredDeeds < 0 || requiredDeeds > 1_000_000) throw error(file, "required_deeds out of range");
            if (minimumStanding < ranks.get(minimumIndex).minimumStanding()) {
                throw error(file, "service minimum_standing is below its minimum rank threshold");
            }
            for (int index = minimumIndex; index < ranks.size(); index++) {
                if (!ranks.get(index).permissions().contains(permission)) {
                    throw error(file, "service permission " + permission + " is missing from qualifying rank "
                            + ranks.get(index).id());
                }
            }
                        long neutralStanding = service.has("neutral_minimum_standing") ? longValue(file, service, "neutral_minimum_standing") : -1;
            int neutralDeeds = service.has("neutral_required_deeds") ? integer(file, service, "neutral_required_deeds") : -1;
            if (service.has("neutral_minimum_standing") != service.has("neutral_required_deeds")
                    || service.has("neutral_minimum_standing") && (neutralStanding < minimumStanding || neutralDeeds < requiredDeeds || neutralDeeds > 1_000_000))
                throw error(file, "neutral alternative must specify both thresholds and cannot be easier than member access");
            services.add(new OrganizationServiceRule(permission, minimumRank, minimumStanding, requiredDeeds, neutralStanding, neutralDeeds));
        }
        JsonArray deedArray = array(file, json, "deeds");
        if (deedArray.size() > 128) throw error(file, "deeds exceeds 128 entries");
        List<OrganizationDeedRule> deeds = new ArrayList<>();
        Set<ResourceLocation> deedIds = new LinkedHashSet<>();
        for (JsonElement element : deedArray) {
            if (!element.isJsonObject()) throw error(file, "deed entries must be objects");
            JsonObject deed = element.getAsJsonObject();
            fields(file, deed, Set.of("quest_id", "credit"));
            ResourceLocation questId = resource(file, string(file, deed, "quest_id"), "deeds.quest_id");
            int credit = integer(file, deed, "credit");
            if (credit < 1 || credit > 10_000) throw error(file, "deed credit must be between 1 and 10000");
            if (!deedIds.add(questId)) throw error(file, "duplicate deed quest " + questId);
            deeds.add(new OrganizationDeedRule(questId, credit));
        }
        return new OrganizationDefinition(expectedId, kind, military, nameKey, descriptionKey, exclusiveGroup,
                conflicts, ranks, services, deeds);
    }

    private static void validateCrossReferences(Map<ResourceLocation, OrganizationDefinition> definitions) {
        definitions.forEach((id, definition) -> definition.conflicts().forEach(conflict -> {
            OrganizationDefinition other = definitions.get(conflict);
            if (other == null) throw error(id, "conflict references unavailable organization " + conflict);
            if (!other.conflicts().contains(id)) throw error(id, "conflict with " + conflict + " is not reciprocal");
        }));
    }

    private static void fields(ResourceLocation file, JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw error(file, "unknown field " + key);
        }
    }

    private static String string(ResourceLocation file, JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw error(file, field + " must be a string");
        }
        String result = value.getAsString();
        if (result.isBlank()) throw error(file, field + " must not be blank");
        return result;
    }

    private static String bounded(ResourceLocation file, String value, String field, int limit) {
        if (value.length() > limit || !value.matches("[a-z0-9_.-]+")) {
            throw error(file, field + " must be a translation key of at most " + limit + " characters");
        }
        return value;
    }

    private static int integer(ResourceLocation file, JsonObject object, String field) {
        JsonElement value = object.get(field);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
            return value.getAsBigDecimal().intValueExact();
        } catch (RuntimeException exception) {
            throw error(file, field + " must be an integer");
        }
    }

    private static boolean bool(ResourceLocation file, JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw error(file, field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static long longValue(ResourceLocation file, JsonObject object, String field) {
        JsonElement value = object.get(field);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
            return value.getAsBigDecimal().longValueExact();
        } catch (RuntimeException exception) {
            throw error(file, field + " must be an integer");
        }
    }

    private static JsonObject object(ResourceLocation file, JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) throw error(file, field + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(ResourceLocation file, JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) throw error(file, field + " must be an array");
        return value.getAsJsonArray();
    }

    private static Optional<ResourceLocation> optionalResource(ResourceLocation file, JsonObject object,
                                                               String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) return Optional.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw error(file, field + " must be a resource id or null");
        }
        return Optional.of(resource(file, value.getAsString(), field));
    }

    private static Set<ResourceLocation> resources(ResourceLocation file, JsonArray values, String field,
                                                   int maximum) {
        if (values.size() > maximum) throw error(file, field + " exceeds " + maximum + " entries");
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw error(file, field + " entries must be resource ids");
            }
            ResourceLocation id = resource(file, value.getAsString(), field);
            if (!result.add(id)) throw error(file, field + " contains duplicate " + id);
        }
        return Set.copyOf(result);
    }

    private static ResourceLocation resource(ResourceLocation file, String value, String field) {
        if (value.length() > 128) throw error(file, field + " resource id is too long");
        ResourceLocation result = ResourceLocation.tryParse(value);
        if (result == null) throw error(file, field + " is not a resource id: " + value);
        return result;
    }

    private static IllegalArgumentException error(ResourceLocation file, String message) {
        return new IllegalArgumentException(file + ": " + message);
    }

    private static IllegalArgumentException error(ResourceLocation file, String message, Throwable cause) {
        return new IllegalArgumentException(file + ": " + message, cause);
    }

    record Snapshot(long generation, Map<ResourceLocation, OrganizationDefinition> definitions) {
        Snapshot {
            definitions = Map.copyOf(definitions);
        }

        static Snapshot empty() {
            return new Snapshot(0L, Map.of());
        }

        Optional<OrganizationDefinition> get(ResourceLocation id) {
            return Optional.ofNullable(definitions.get(id));
        }

        List<OrganizationDefinition> ordered() {
            return definitions.values().stream().sorted(Comparator.comparing(value -> value.id().toString())).toList();
        }
    }
}
