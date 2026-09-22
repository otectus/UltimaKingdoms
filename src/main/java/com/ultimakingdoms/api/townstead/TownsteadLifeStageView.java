package com.ultimakingdoms.api.townstead;

import java.util.Objects;

public record TownsteadLifeStageView(
        String id, String label, int days, float scale, String presentsAs,
        float narrativeStart, float narrativeEnd
) {
    public TownsteadLifeStageView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(presentsAs, "presentsAs");
    }
}
