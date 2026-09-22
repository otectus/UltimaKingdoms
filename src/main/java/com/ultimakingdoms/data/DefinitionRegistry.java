package com.ultimakingdoms.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultimakingdoms.kingdom.BiomeRule;
import com.ultimakingdoms.kingdom.KingdomDefinition;
import com.ultimakingdoms.kingdom.StructureStyleRule;
import com.ultimakingdoms.naming.NamePool;
import com.ultimakingdoms.naming.NameTemplate;
import com.ultimakingdoms.naming.VillageNameGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class DefinitionRegistry {
    private static final String ROOT = "ultima_kingdoms/";
    private static final String KINGDOMS = "kingdoms/";
    private static final String NAME_POOLS = "name_pools/";
    private static final String BIOME_RULES = "biome_rules/";
    private static final String STRUCTURE_STYLES = "structure_styles/";
    private static final String KINGDOM_GATES = "kingdom_gates/";

    private final AtomicReference<DefinitionSnapshot> active = new AtomicReference<>(DefinitionSnapshot.empty());
    private final AtomicReference<DefinitionSnapshot> pending = new AtomicReference<>();
    private final AtomicLong revisions = new AtomicLong();
    private final PreparableReloadListener reloadListener = new Loader();

    public DefinitionRegistry() {
    }

    public PreparableReloadListener reloadListener() {
        return reloadListener;
    }

    public void commitPending() {
        DefinitionSnapshot staged = pending.getAndSet(null);
        if (staged != null) {
            active.set(staged.withRevision(revisions.incrementAndGet()));
        }
    }

    public DefinitionSnapshot snapshot() {
        return active.get();
    }

    public void clear() {
        pending.set(null);
        active.set(DefinitionSnapshot.empty());
        revisions.set(0L);
    }

    private final class Loader extends SimplePreparableReloadListener<DefinitionSnapshot> {
        @Override
        protected DefinitionSnapshot prepare(ResourceManager resources, ProfilerFiller profiler) {
            pending.set(null);
            try {
                return parse(resources);
            } catch (RuntimeException exception) {
                pending.set(null);
                throw exception;
            }
        }

        @Override
        protected void apply(DefinitionSnapshot snapshot, ResourceManager resources, ProfilerFiller profiler) {
            pending.set(snapshot);
        }
    }

    private static DefinitionSnapshot parse(ResourceManager resources) {
        Map<ResourceLocation, KingdomDefinition> kingdoms = new LinkedHashMap<>();
        Map<ResourceLocation, NamePool> pools = new LinkedHashMap<>();
        List<BiomeRule> biomeRules = new ArrayList<>();
        List<StructureStyleRule> styles = new ArrayList<>();

        resources.listResources("ultima_kingdoms", location -> location.getPath().endsWith(".json")).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ResourceLocation file = entry.getKey();
                    String relative = file.getPath().substring(ROOT.length());
                    // Shared gates have their own transactional integration registry.
                    if (relative.startsWith(KINGDOM_GATES) || java.util.Set.of("governments", "offices", "agreements", "petitions", "honors", "institution_charters")
                            .contains(relative.split("/", 2)[0])) return;
                    try (Reader reader = entry.getValue().openAsReader()) {
                        JsonElement parsed = JsonParser.parseReader(reader);
                        if (!parsed.isJsonObject()) {
                            throw error(file, "root must be a JSON object");
                        }
                        JsonObject json = parsed.getAsJsonObject();
                        if (relative.startsWith(KINGDOMS)) {
                            ResourceLocation id = logicalId(file, relative, KINGDOMS);
                            putUnique(kingdoms, id, parseKingdom(file, id, json));
                        } else if (relative.startsWith(NAME_POOLS)) {
                            ResourceLocation id = logicalId(file, relative, NAME_POOLS);
                            putUnique(pools, id, parseNamePool(file, id, json));
                        } else if (relative.startsWith(BIOME_RULES)) {
                            ResourceLocation id = logicalId(file, relative, BIOME_RULES);
                            biomeRules.add(parseBiomeRule(file, id, json));
                        } else if (relative.startsWith(STRUCTURE_STYLES)) {
                            ResourceLocation id = logicalId(file, relative, STRUCTURE_STYLES);
                            styles.add(parseStyleRule(file, id, json));
                        } else {
                            throw error(file, "unknown definition folder");
                        }
                    } catch (IOException exception) {
                        throw new IllegalArgumentException("Failed reading definition " + file, exception);
                    }
                });

        if (kingdoms.isEmpty()) {
            throw new IllegalArgumentException("No Ultima Kingdoms definitions were found");
        }
        kingdoms.values().forEach(kingdom -> {
            if (!pools.containsKey(kingdom.namePool())) {
                throw new IllegalArgumentException("Kingdom " + kingdom.id() + " references missing name pool "
                        + kingdom.namePool());
            }
        });
        biomeRules.forEach(rule -> requireKingdom(kingdoms, rule.kingdomId(), "biome rule " + rule.id()));
        styles.forEach(rule -> requireKingdom(kingdoms, rule.kingdomId(), "structure style rule " + rule.id()));
        List<ResourceLocation> fallbacks = kingdoms.values().stream().filter(KingdomDefinition::fallback)
                .map(KingdomDefinition::id).sorted().toList();
        if (fallbacks.size() != 1) {
            throw new IllegalArgumentException("Exactly one kingdom must declare fallback=true; found " + fallbacks);
        }
        return new DefinitionSnapshot(kingdoms, pools, biomeRules, styles, fallbacks.get(0), 0L);
    }

    private static KingdomDefinition parseKingdom(ResourceLocation file, ResourceLocation id, JsonObject json) {
        schema(file, json);
        optionalMatchingId(file, id, json);
        String translationKey = string(file, json, "translation_key");
        ResourceLocation namePool = resource(file, string(file, json, "name_pool"), "name_pool");
        JsonObject heraldry = object(file, json, "heraldry");
        ResourceLocation icon = resource(file, string(file, heraldry, "icon"), "heraldry.icon");
        int color = color(file, heraldry.get("color"));
        Map<String, String> metadata = json.has("metadata") ? stringMap(file, object(file, json, "metadata")) : Map.of();
        Set<ResourceLocation> styleHints = new LinkedHashSet<>();
        for (String value : strings(file, json, "style_hints", false)) {
            styleHints.add(resource(file, value, "style_hints"));
        }
        boolean fallback = json.has("fallback") && bool(file, json, "fallback");
        return new KingdomDefinition(id, translationKey, namePool, icon, color, metadata, styleHints, fallback);
    }

    private static NamePool parseNamePool(ResourceLocation file, ResourceLocation id, JsonObject json) {
        schema(file, json);
        optionalMatchingId(file, id, json);
        List<String> canonical = strings(file, json, "canonical", true);
        Set<String> canonicalKeys = new HashSet<>();
        for (String name : canonical) {
            String normalized = VillageNameGenerator.normalize(name);
            if (normalized.isEmpty() || !canonicalKeys.add(normalized)) {
                throw error(file, "canonical names must have unique, non-empty normalized forms: " + name);
            }
        }
        Set<String> reserved = normalizedStrings(file, json, "reserved");
        Set<String> blacklist = normalizedStrings(file, json, "blacklist");
        JsonArray templateArray = array(file, json, "templates");
        List<NameTemplate> templates = new ArrayList<>();
        for (JsonElement element : templateArray) {
            if (!element.isJsonObject()) {
                throw error(file, "templates entries must be objects");
            }
            JsonObject template = element.getAsJsonObject();
            templates.add(new NameTemplate(string(file, template, "format"), integer(file, template, "weight")));
        }
        JsonObject tokenObject = object(file, json, "tokens");
        Map<String, List<String>> tokens = new LinkedHashMap<>();
        tokenObject.entrySet().forEach(entry -> {
            if (!entry.getKey().matches("[a-z0-9_]+")) {
                throw error(file, "invalid token key " + entry.getKey());
            }
            tokens.put(entry.getKey(), stringArray(file, entry.getValue(), "tokens." + entry.getKey(), true));
        });
        int retries = json.has("retries") ? integer(file, json, "retries") : 64;
        return new NamePool(id, canonical, reserved, blacklist, templates, tokens, retries);
    }

    private static BiomeRule parseBiomeRule(ResourceLocation file, ResourceLocation id, JsonObject json) {
        schema(file, json);
        optionalMatchingId(file, id, json);
        ResourceLocation kingdom = resource(file, string(file, json, "kingdom"), "kingdom");
        int priority = integer(file, json, "priority");
        List<BiomeRule.Selector> include = selectors(file, json, "include", true);
        List<BiomeRule.Selector> exclude = selectors(file, json, "exclude", false);
        boolean environmental = json.has("environmental") && bool(file, json, "environmental");
        return new BiomeRule(id, kingdom, priority, include, exclude, environmental);
    }

    private static StructureStyleRule parseStyleRule(ResourceLocation file, ResourceLocation id, JsonObject json) {
        schema(file, json);
        optionalMatchingId(file, id, json);
        ResourceLocation kingdom = resource(file, string(file, json, "kingdom"), "kingdom");
        int priority = json.has("priority") ? integer(file, json, "priority") : 0;
        Set<ResourceLocation> styles = new LinkedHashSet<>();
        for (String value : strings(file, json, "styles", true)) {
            styles.add(resource(file, value, "styles"));
        }
        return new StructureStyleRule(id, kingdom, priority, styles);
    }

    private static List<BiomeRule.Selector> selectors(ResourceLocation file, JsonObject json, String field,
                                                        boolean required) {
        List<BiomeRule.Selector> result = new ArrayList<>();
        for (String value : strings(file, json, field, required)) {
            boolean tag = value.startsWith("#");
            String raw = tag ? value.substring(1) : value;
            result.add(new BiomeRule.Selector(resource(file, raw, field), tag));
        }
        return result;
    }

    private static Set<String> normalizedStrings(ResourceLocation file, JsonObject json, String field) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : strings(file, json, field, false)) {
            String normalized = VillageNameGenerator.normalize(value);
            if (normalized.isEmpty()) {
                throw error(file, field + " contains a value with an empty normalized form");
            }
            result.add(normalized);
        }
        return result;
    }

    private static void schema(ResourceLocation file, JsonObject json) {
        int schema = integer(file, json, "schema");
        if (schema != 1) {
            throw error(file, "unsupported schema " + schema + "; expected 1");
        }
    }

    private static void optionalMatchingId(ResourceLocation file, ResourceLocation expected, JsonObject json) {
        if (json.has("id")) {
            ResourceLocation declared = resource(file, string(file, json, "id"), "id");
            if (!expected.equals(declared)) {
                throw error(file, "declared id " + declared + " does not match file id " + expected);
            }
        }
    }

    private static ResourceLocation logicalId(ResourceLocation file, String relative, String folder) {
        String path = relative.substring(folder.length(), relative.length() - ".json".length());
        if (path.isBlank()) {
            throw error(file, "definition file has no logical id");
        }
        return resource(file, file.getNamespace() + ":" + path, "file name");
    }

    private static ResourceLocation resource(ResourceLocation file, String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw error(file, field + " is not a valid resource location: " + value);
        }
        return id;
    }

    private static JsonObject object(ResourceLocation file, JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonObject()) {
            throw error(file, field + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(ResourceLocation file, JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonArray()) {
            throw error(file, field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static List<String> strings(ResourceLocation file, JsonObject json, String field, boolean required) {
        if (!json.has(field)) {
            if (required) {
                throw error(file, "missing required array " + field);
            }
            return List.of();
        }
        return stringArray(file, json.get(field), field, required);
    }

    private static List<String> stringArray(ResourceLocation file, JsonElement value, String field, boolean required) {
        if (!value.isJsonArray()) {
            throw error(file, field + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || element.getAsString().isBlank()) {
                throw error(file, field + " must contain non-blank strings");
            }
            result.add(element.getAsString().strip());
        }
        if (required && result.isEmpty()) {
            throw error(file, field + " must not be empty");
        }
        return List.copyOf(result);
    }

    private static String string(ResourceLocation file, JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw error(file, field + " must be a non-blank string");
        }
        return value.getAsString().strip();
    }

    private static int integer(ResourceLocation file, JsonObject json, String field) {
        JsonElement value = json.get(field);
        try {
            if (value == null || !value.isJsonPrimitive()) {
                throw new NumberFormatException();
            }
            return value.getAsInt();
        } catch (RuntimeException exception) {
            throw error(file, field + " must be an integer");
        }
    }

    private static boolean bool(ResourceLocation file, JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !(value.getAsJsonPrimitive().isBoolean())) {
            throw error(file, field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int color(ResourceLocation file, JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            throw error(file, "heraldry.color must be an integer or #RRGGBB string");
        }
        try {
            if (value.getAsJsonPrimitive().isNumber()) {
                int color = value.getAsInt();
                if (color < 0 || color > 0xFFFFFF) {
                    throw new NumberFormatException();
                }
                return color;
            }
            String string = value.getAsString();
            if (!string.matches("#[0-9a-fA-F]{6}")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(string.substring(1), 16);
        } catch (RuntimeException exception) {
            throw error(file, "heraldry.color must be an RGB integer or #RRGGBB string");
        }
    }

    private static Map<String, String> stringMap(ResourceLocation file, JsonObject object) {
        Map<String, String> result = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw error(file, "metadata values must be strings");
            }
            result.put(entry.getKey(), entry.getValue().getAsString());
        });
        return Map.copyOf(result);
    }

    private static void requireKingdom(Map<ResourceLocation, KingdomDefinition> kingdoms, ResourceLocation id,
                                       String owner) {
        if (!kingdoms.containsKey(id)) {
            throw new IllegalArgumentException(owner + " references undefined kingdom " + id);
        }
    }

    private static <T> void putUnique(Map<ResourceLocation, T> map, ResourceLocation id, T value) {
        if (map.putIfAbsent(id, value) != null) {
            throw new IllegalArgumentException("Duplicate definition " + id);
        }
    }

    private static IllegalArgumentException error(ResourceLocation file, String message) {
        return new IllegalArgumentException("Invalid Ultima Kingdoms definition " + file + ": " + message);
    }
}
