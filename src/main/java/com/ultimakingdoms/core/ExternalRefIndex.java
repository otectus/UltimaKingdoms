package com.ultimakingdoms.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Maintained reverse index for persisted settlement external references. */
final class ExternalRefIndex {
    private final Map<Key, UUID> owners = new LinkedHashMap<>();

    Optional<UUID> find(String namespace, String value) {
        return Optional.ofNullable(owners.get(new Key(namespace, value)));
    }

    void rebuild(Collection<SettlementRecord> records) {
        Map<Key, UUID> rebuilt = new LinkedHashMap<>();
        for (SettlementRecord record : records) {
            putAll(rebuilt, record.id, record.externalRefValues, Set.of(record.id));
        }
        owners.clear();
        owners.putAll(rebuilt);
    }

    void reindex(SettlementRecord record) {
        Map<Key, UUID> updated = new LinkedHashMap<>(owners);
        updated.entrySet().removeIf(entry -> entry.getValue().equals(record.id));
        putAll(updated, record.id, record.externalRefValues, Set.of(record.id));
        owners.clear();
        owners.putAll(updated);
    }

    void validate(UUID owner, Map<String, String> references, Set<UUID> compatibleOwners) {
        Map<Key, UUID> checked = new LinkedHashMap<>(owners);
        Objects.requireNonNull(references, "references").forEach((namespace, value) ->
                put(checked, new Key(namespace, value), owner, compatibleOwners));
    }

    void validateAll(UUID owner, Map<String, Set<String>> references, Set<UUID> compatibleOwners) {
        Map<Key, UUID> checked = new LinkedHashMap<>(owners);
        putAll(checked, owner, references, compatibleOwners);
    }

    void redirect(UUID source, UUID target) {
        owners.replaceAll((ignored, owner) -> owner.equals(source) ? target : owner);
    }

    private static void putAll(Map<Key, UUID> target, UUID owner, Map<String, Set<String>> references,
                               Set<UUID> compatibleOwners) {
        references.forEach((namespace, values) -> values.forEach(value ->
                put(target, new Key(namespace, value), owner, compatibleOwners)));
    }

    private static void put(Map<Key, UUID> target, Key key, UUID owner, Set<UUID> compatibleOwners) {
        UUID existing = target.get(key);
        if (existing != null && !compatibleOwners.contains(existing)) {
            throw new IllegalStateException("External reference " + key.namespace + "=" + key.value
                    + " is already owned by settlement " + existing + "; cannot assign it to " + owner);
        }
        target.put(key, owner);
    }

    private record Key(String namespace, String value) {
        private Key {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(value, "value");
            if (namespace.isBlank()) throw new IllegalArgumentException("External reference namespace must not be blank");
            if (value.isBlank()) throw new IllegalArgumentException("External reference value must not be blank");
        }
    }
}
