package com.ultimakingdoms.api.townstead;

import java.util.Objects;

public record TownsteadGeneVariantView(String id, String displayName, int weight, String type) {
    public TownsteadGeneVariantView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(type, "type");
    }
}
