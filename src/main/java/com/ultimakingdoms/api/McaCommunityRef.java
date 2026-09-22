package com.ultimakingdoms.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

/**
 * MCA village identity as stored in an Ultima Kingdoms external reference.
 *
 * <p>This type deliberately has no compile-time dependency on MCA. MCA village ids are allocated
 * within a dimension, so both fields are required to identify a community.</p>
 */
public record McaCommunityRef(ResourceLocation dimension, int villageId) {
    public static final String EXTERNAL_REF_NAMESPACE = "mca";

    public McaCommunityRef {
        Objects.requireNonNull(dimension, "dimension");
        if (villageId < 0) {
            throw new IllegalArgumentException("MCA village id must be non-negative: " + villageId);
        }
    }

    /** Returns the persisted external-reference form {@code <dimension>#<villageId>}. */
    public String format() {
        return dimension + "#" + villageId;
    }

    /** Parses the persisted external-reference form, returning empty for malformed input. */
    public static Optional<McaCommunityRef> parse(String value) {
        if (value == null) return Optional.empty();
        int separator = value.lastIndexOf('#');
        if (separator <= 0 || separator == value.length() - 1) return Optional.empty();
        ResourceLocation dimension = ResourceLocation.tryParse(value.substring(0, separator));
        if (dimension == null) return Optional.empty();
        try {
            int villageId = Integer.parseInt(value.substring(separator + 1));
            return villageId < 0 ? Optional.empty() : Optional.of(new McaCommunityRef(dimension, villageId));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return format();
    }
}
