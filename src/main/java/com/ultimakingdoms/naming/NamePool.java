package com.ultimakingdoms.naming;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record NamePool(
        ResourceLocation id,
        List<String> canonical,
        Set<String> reserved,
        Set<String> blacklist,
        List<NameTemplate> templates,
        Map<String, List<String>> tokens,
        int retries
) {
    public NamePool {
        Objects.requireNonNull(id, "id");
        canonical = List.copyOf(Objects.requireNonNull(canonical, "canonical"));
        reserved = Set.copyOf(Objects.requireNonNull(reserved, "reserved"));
        blacklist = Set.copyOf(Objects.requireNonNull(blacklist, "blacklist"));
        templates = List.copyOf(Objects.requireNonNull(templates, "templates"));
        Map<String, List<String>> immutableTokens = Objects.requireNonNull(tokens, "tokens").entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        tokens = immutableTokens;
        if (canonical.isEmpty() || templates.isEmpty()) {
            throw new IllegalArgumentException("Name pool " + id + " requires canonical names and templates");
        }
        if (retries < 1 || retries > 256) {
            throw new IllegalArgumentException("Name pool retries must be from 1 to 256");
        }
        templates.forEach(template -> template.validateTokens(immutableTokens));
    }
}
