package com.ultimakingdoms.api.gating;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A strict, immutable kingdom allow/deny predicate shared by integration adapters. */
public record KingdomPredicate(
        KingdomSubject subject,
        Set<ResourceLocation> include,
        Set<ResourceLocation> exclude,
        UnknownKingdomPolicy whenUnknown
) {
    private static final Set<String> FIELDS = Set.of("subject", "include", "exclude", "when_unknown");

    public static final Codec<KingdomPredicate> CODEC = Codec.PASSTHROUGH.comapFlatMap(
            dynamic -> {
                try {
                    JsonElement json = dynamic.convert(JsonOps.INSTANCE).getValue();
                    return DataResult.success(fromJson(json));
                } catch (IllegalArgumentException exception) {
                    return DataResult.error(exception::getMessage);
                }
            },
            predicate -> new Dynamic<>(JsonOps.INSTANCE, predicate.toJson()));

    public KingdomPredicate {
        subject = Objects.requireNonNull(subject, "subject");
        include = Set.copyOf(Objects.requireNonNull(include, "include"));
        exclude = Set.copyOf(Objects.requireNonNull(exclude, "exclude"));
        whenUnknown = Objects.requireNonNull(whenUnknown, "whenUnknown");
    }

    public static KingdomPredicate fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("Kingdom predicate must be a JSON object");
        }
        JsonObject json = element.getAsJsonObject();
        for (String field : json.keySet()) {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown kingdom predicate field: " + field);
            }
        }
        KingdomSubject subject = json.has("subject")
                ? KingdomSubject.fromSerializedName(requireString(json, "subject"))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown kingdom subject: " + requireString(json, "subject")))
                : KingdomSubject.GIVER_RESIDENCE;
        UnknownKingdomPolicy policy = json.has("when_unknown")
                ? UnknownKingdomPolicy.fromSerializedName(requireString(json, "when_unknown"))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown kingdom fallback policy: " + requireString(json, "when_unknown")))
                : UnknownKingdomPolicy.DENY;
        return new KingdomPredicate(subject, ids(json, "include"), ids(json, "exclude"), policy);
    }

    public boolean matches(ResourceLocation kingdomId) {
        if (kingdomId == null) return whenUnknown == UnknownKingdomPolicy.ALLOW;
        if (exclude.contains(kingdomId)) return false;
        return include.isEmpty() || include.contains(kingdomId);
    }

    public boolean matches(Optional<KingdomContext> context) {
        Objects.requireNonNull(context, "context");
        return context.map(KingdomContext::kingdomId).map(this::matches)
                .orElse(whenUnknown == UnknownKingdomPolicy.ALLOW);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("subject", subject.serializedName());
        json.add("include", ids(include));
        json.add("exclude", ids(exclude));
        json.addProperty("when_unknown", whenUnknown.serializedName());
        return json;
    }

    private static Set<ResourceLocation> ids(JsonObject json, String field) {
        if (!json.has(field)) return Set.of();
        JsonElement element = json.get(field);
        if (!element.isJsonArray()) throw new IllegalArgumentException(field + " must be an array");
        LinkedHashSet<ResourceLocation> ids = new LinkedHashSet<>();
        int index = 0;
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(field + "[" + index + "] must be a resource id string");
            }
            String raw = value.getAsString();
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null || !id.toString().equals(raw)) {
                throw new IllegalArgumentException(field + "[" + index + "] is not a canonical resource id: " + raw);
            }
            if (!ids.add(id)) throw new IllegalArgumentException(field + " contains duplicate id: " + id);
            index++;
        }
        return Set.copyOf(ids);
    }

    private static JsonArray ids(Set<ResourceLocation> ids) {
        JsonArray json = new JsonArray();
        ids.stream().sorted().forEach(id -> json.add(id.toString()));
        return json;
    }

    private static String requireString(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }
}
