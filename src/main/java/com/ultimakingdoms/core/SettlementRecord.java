package com.ultimakingdoms.core;

import com.ultimakingdoms.api.AssignmentSource;
import com.ultimakingdoms.api.DetectionSource;
import com.ultimakingdoms.api.SettlementBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class SettlementRecord {
    static final int SCHEMA = 1;
    private static final ResourceLocation BUILTIN_VILLAGE_STRUCTURE =
            new ResourceLocation("ultima_kingdoms", "village_structure");

    UUID id;
    ResourceKey<Level> dimension;
    BlockPos anchor;
    int radius;
    SettlementBounds bounds;
    ResourceLocation kingdomId;
    String displayName;
    ResourceLocation slug;
    ResourceLocation biomeAtCreation;
    ResourceLocation styleId;
    AssignmentSource assignmentSource;
    DetectionSource detectionSource;
    boolean nameLocked;
    boolean kingdomLocked;
    long createdGameTime;
    long lastObservedGameTime;
    final Map<String, String> externalRefs = new LinkedHashMap<>();
    final Map<String, Set<String>> externalRefValues = new LinkedHashMap<>();
    final List<String> aliases = new ArrayList<>();
    final Set<ResourceLocation> retiredSlugs = new java.util.LinkedHashSet<>();
    final List<String> assignmentTrace = new ArrayList<>();
    ResourceLocation sourceId;
    String sourceKey;
    final Map<ResourceLocation, Set<String>> detectorIdentities = new LinkedHashMap<>();
    final Map<ResourceLocation, Set<String>> strongStructureIdentities = new LinkedHashMap<>();
    long revision;

    SettlementSnapshot snapshot() {
        return new SettlementSnapshot(id, dimension, anchor, radius, bounds, kingdomId, displayName, slug,
                biomeAtCreation, Optional.ofNullable(styleId), assignmentSource, detectionSource, nameLocked,
                kingdomLocked, createdGameTime, lastObservedGameTime, externalRefs, aliases, assignmentTrace,
                revision);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", SCHEMA);
        tag.putUUID("Id", id);
        tag.putString("Dimension", dimension.location().toString());
        tag.putLong("Anchor", anchor.asLong());
        tag.putInt("Radius", radius);
        tag.putInt("MinX", bounds.minX());
        tag.putInt("MinZ", bounds.minZ());
        tag.putInt("MaxX", bounds.maxX());
        tag.putInt("MaxZ", bounds.maxZ());
        tag.putString("Kingdom", kingdomId.toString());
        tag.putString("DisplayName", displayName);
        tag.putString("Slug", slug.toString());
        tag.putString("Biome", biomeAtCreation.toString());
        if (styleId != null) tag.putString("Style", styleId.toString());
        tag.putString("AssignmentSource", assignmentSource.name());
        tag.putString("DetectionSource", detectionSource.name());
        tag.putBoolean("NameLocked", nameLocked);
        tag.putBoolean("KingdomLocked", kingdomLocked);
        tag.putLong("Created", createdGameTime);
        tag.putLong("LastObserved", lastObservedGameTime);
        tag.put("ExternalRefs", writeMap(externalRefs));
        tag.put("ExternalRefValues", writeStringSets(externalRefValues));
        tag.put("Aliases", writeStrings(aliases));
        tag.put("RetiredSlugs", writeResourceLocations(retiredSlugs));
        tag.put("AssignmentTrace", writeStrings(assignmentTrace));
        tag.putString("SourceId", sourceId.toString());
        tag.putString("SourceKey", sourceKey);
        tag.put("DetectorIdentities", writeResourceLocationSets(detectorIdentities));
        tag.put("StrongStructureIdentities", writeResourceLocationSets(strongStructureIdentities));
        tag.putLong("Revision", revision);
        return tag;
    }

    static SettlementRecord load(CompoundTag tag) {
        int schema = tag.getInt("Schema");
        if (schema != SCHEMA) {
            throw new IllegalArgumentException("Unsupported settlement schema " + schema);
        }
        SettlementRecord record = new SettlementRecord();
        record.id = tag.getUUID("Id");
        record.dimension = ResourceKey.create(Registries.DIMENSION, requiredId(tag.getString("Dimension"), "dimension"));
        record.anchor = BlockPos.of(tag.getLong("Anchor"));
        record.radius = tag.getInt("Radius");
        record.bounds = new SettlementBounds(tag.getInt("MinX"), tag.getInt("MinZ"), tag.getInt("MaxX"), tag.getInt("MaxZ"));
        record.kingdomId = requiredId(tag.getString("Kingdom"), "kingdom");
        record.displayName = tag.getString("DisplayName");
        record.slug = requiredId(tag.getString("Slug"), "slug");
        record.biomeAtCreation = requiredId(tag.getString("Biome"), "biome");
        record.styleId = tag.contains("Style", Tag.TAG_STRING) ? requiredId(tag.getString("Style"), "style") : null;
        record.assignmentSource = enumValue(AssignmentSource.class, tag.getString("AssignmentSource"), AssignmentSource.FALLBACK);
        record.detectionSource = enumValue(DetectionSource.class, tag.getString("DetectionSource"), DetectionSource.ADDON);
        record.nameLocked = tag.getBoolean("NameLocked");
        record.kingdomLocked = tag.getBoolean("KingdomLocked");
        record.createdGameTime = tag.getLong("Created");
        record.lastObservedGameTime = tag.getLong("LastObserved");
        record.externalRefs.putAll(readMap(tag.getCompound("ExternalRefs")));
        record.externalRefValues.putAll(readStringSets(tag.getList("ExternalRefValues", Tag.TAG_COMPOUND)));
        record.externalRefs.forEach(record::addExternalRef);
        record.aliases.addAll(readStrings(tag.getList("Aliases", Tag.TAG_STRING)));
        record.retiredSlugs.addAll(readResourceLocations(tag.getList("RetiredSlugs", Tag.TAG_STRING)));
        record.assignmentTrace.addAll(readStrings(tag.getList("AssignmentTrace", Tag.TAG_STRING)));
        record.sourceId = requiredId(tag.getString("SourceId"), "source id");
        record.sourceKey = tag.getString("SourceKey");
        record.detectorIdentities.putAll(readResourceLocationSets(
                tag.getList("DetectorIdentities", Tag.TAG_COMPOUND)));
        record.addDetectorIdentity(record.sourceId, record.sourceKey);
        record.strongStructureIdentities.putAll(readResourceLocationSets(
                tag.getList("StrongStructureIdentities", Tag.TAG_COMPOUND)));
        if (record.detectionSource == DetectionSource.STRUCTURE) {
            record.addStrongStructureIdentity(record.sourceId, record.sourceKey);
        }
        record.detectorIdentities.getOrDefault(BUILTIN_VILLAGE_STRUCTURE, Set.of())
                .forEach(key -> record.addStrongStructureIdentity(BUILTIN_VILLAGE_STRUCTURE, key));
        record.revision = tag.getLong("Revision");
        return record;
    }

    void addDetectorIdentity(ResourceLocation detector, String key) {
        detectorIdentities.computeIfAbsent(detector, ignored -> new java.util.LinkedHashSet<>()).add(key);
    }

    boolean hasDetectorIdentity(ResourceLocation detector, String key) {
        return detectorIdentities.getOrDefault(detector, Set.of()).contains(key);
    }

    void addStrongStructureIdentity(ResourceLocation detector, String key) {
        addDetectorIdentity(detector, key);
        strongStructureIdentities.computeIfAbsent(detector, ignored -> new java.util.LinkedHashSet<>()).add(key);
    }

    boolean hasStrongStructureIdentities() {
        return strongStructureIdentities.values().stream().anyMatch(keys -> !keys.isEmpty());
    }

    boolean addExternalRef(String namespace, String value) {
        externalRefs.putIfAbsent(namespace, value);
        return externalRefValues.computeIfAbsent(namespace, ignored -> new java.util.LinkedHashSet<>()).add(value);
    }

    boolean hasExternalRef(String namespace, String value) {
        return externalRefValues.getOrDefault(namespace, Set.of()).contains(value);
    }

    private static CompoundTag writeMap(Map<String, String> values) {
        CompoundTag result = new CompoundTag();
        values.forEach(result::putString);
        return result;
    }

    private static Map<String, String> readMap(CompoundTag tag) {
        Map<String, String> result = new LinkedHashMap<>();
        tag.getAllKeys().forEach(key -> result.put(key, tag.getString(key)));
        return result;
    }

    private static ListTag writeStrings(List<String> values) {
        ListTag result = new ListTag();
        values.forEach(value -> result.add(StringTag.valueOf(value)));
        return result;
    }

    private static List<String> readStrings(ListTag tag) {
        List<String> result = new ArrayList<>(tag.size());
        for (int i = 0; i < tag.size(); i++) result.add(tag.getString(i));
        return result;
    }

    private static ListTag writeResourceLocations(Set<ResourceLocation> values) {
        ListTag result = new ListTag();
        values.forEach(value -> result.add(StringTag.valueOf(value.toString())));
        return result;
    }

    private static Set<ResourceLocation> readResourceLocations(ListTag tag) {
        Set<ResourceLocation> result = new java.util.LinkedHashSet<>();
        for (int i = 0; i < tag.size(); i++) {
            ResourceLocation value = ResourceLocation.tryParse(tag.getString(i));
            if (value != null) result.add(value);
        }
        return result;
    }

    private static ListTag writeStringSets(Map<String, Set<String>> values) {
        ListTag result = new ListTag();
        values.forEach((key, entries) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Key", key);
            entry.put("Values", writeStrings(List.copyOf(entries)));
            result.add(entry);
        });
        return result;
    }

    private static Map<String, Set<String>> readStringSets(ListTag tag) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < tag.size(); i++) {
            CompoundTag entry = tag.getCompound(i);
            result.put(entry.getString("Key"), new java.util.LinkedHashSet<>(
                    readStrings(entry.getList("Values", Tag.TAG_STRING))));
        }
        return result;
    }

    private static ListTag writeResourceLocationSets(Map<ResourceLocation, Set<String>> values) {
        Map<String, Set<String>> serialized = new LinkedHashMap<>();
        values.forEach((key, entries) -> serialized.put(key.toString(), entries));
        return writeStringSets(serialized);
    }

    private static Map<ResourceLocation, Set<String>> readResourceLocationSets(ListTag tag) {
        Map<ResourceLocation, Set<String>> result = new LinkedHashMap<>();
        readStringSets(tag).forEach((key, entries) -> {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null) result.put(id, entries);
        });
        return result;
    }

    private static ResourceLocation requiredId(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException("Invalid settlement " + field + ": " + value);
        return id;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
