package com.ultimakingdoms.presentation;

import com.ultimakingdoms.api.AssignmentSource;
import com.ultimakingdoms.api.SettlementView;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record SettlementSummary(
        UUID id,
        String displayName,
        ResourceLocation slug,
        ResourceLocation kingdomId,
        ResourceLocation dimension,
        BlockPos anchor,
        ResourceLocation biome,
        AssignmentSource assignmentSource,
        long recognizedGameTime,
        long revision
) {
    public SettlementSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(kingdomId, "kingdomId");
        Objects.requireNonNull(dimension, "dimension");
        anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(assignmentSource, "assignmentSource");
    }

    public static SettlementSummary from(SettlementView view) {
        return new SettlementSummary(view.id(), view.displayName(), view.slug(), view.kingdomId(),
                view.dimension().location(), view.anchor(), view.biomeAtCreation(), view.assignmentSource(),
                view.createdGameTime(), view.revision());
    }

    public static SettlementSummary decode(FriendlyByteBuf buffer) {
        return new SettlementSummary(buffer.readUUID(), buffer.readUtf(128), buffer.readResourceLocation(),
                buffer.readResourceLocation(), buffer.readResourceLocation(), buffer.readBlockPos(),
                buffer.readResourceLocation(), buffer.readEnum(AssignmentSource.class),
                buffer.readVarLong(), buffer.readVarLong());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeUtf(displayName, 128);
        buffer.writeResourceLocation(slug);
        buffer.writeResourceLocation(kingdomId);
        buffer.writeResourceLocation(dimension);
        buffer.writeBlockPos(anchor);
        buffer.writeResourceLocation(biome);
        buffer.writeEnum(assignmentSource);
        buffer.writeVarLong(recognizedGameTime);
        buffer.writeVarLong(revision);
    }
}
