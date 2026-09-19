package com.ultimakingdoms.core;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class SettlementSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "ultima_kingdoms_settlements";
    private static final int SCHEMA = 1;

    private final Map<UUID, SettlementRecord> settlements = new LinkedHashMap<>();
    private final Map<UUID, UUID> redirects = new LinkedHashMap<>();
    private long revision;
    private String loadError;

    static SettlementSavedData get(MinecraftServer server) {
        SettlementSavedData data = server.overworld().getDataStorage().computeIfAbsent(
                SettlementSavedData::load,
                SettlementSavedData::new,
                DATA_NAME
        );
        data.validateLoaded();
        return data;
    }

    Collection<SettlementRecord> records() {
        return settlements.values();
    }

    Optional<SettlementRecord> get(UUID requestedId) {
        return Optional.ofNullable(settlements.get(resolveId(requestedId)));
    }

    UUID resolveId(UUID requestedId) {
        UUID current = requestedId;
        for (int i = 0; i < 64; i++) {
            UUID next = redirects.get(current);
            if (next == null || next.equals(current)) return current;
            current = next;
        }
        throw new IllegalStateException("Settlement redirect cycle involving " + requestedId);
    }

    long revision() {
        return revision;
    }

    void add(SettlementRecord record) {
        if (settlements.putIfAbsent(record.id, record) != null) {
            throw new IllegalArgumentException("Settlement already exists: " + record.id);
        }
        changed(record);
    }

    void changed(SettlementRecord record) {
        record.revision = ++revision;
        setDirty();
    }

    void redirect(UUID source, UUID target) {
        UUID resolvedTarget = resolveId(target);
        if (source.equals(resolvedTarget)) {
            throw new IllegalArgumentException("Cannot redirect a settlement to itself");
        }
        settlements.remove(source);
        redirects.put(source, resolvedTarget);
        redirects.replaceAll((ignored, value) -> value.equals(source) ? resolvedTarget : value);
        revision++;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("Schema", SCHEMA);
        tag.putLong("Revision", revision);
        ListTag settlementList = new ListTag();
        settlements.values().forEach(record -> settlementList.add(record.save()));
        tag.put("Settlements", settlementList);

        ListTag redirectList = new ListTag();
        redirects.forEach((source, target) -> {
            CompoundTag redirect = new CompoundTag();
            redirect.putUUID("Source", source);
            redirect.putUUID("Target", target);
            redirectList.add(redirect);
        });
        tag.put("Redirects", redirectList);
        return tag;
    }

    private static SettlementSavedData load(CompoundTag tag) {
        SettlementSavedData data = new SettlementSavedData();
        int schema = tag.getInt("Schema");
        if (schema != SCHEMA) {
            data.loadError = "Unsupported Ultima Kingdoms save schema " + schema
                    + "; refusing to initialize so the original save cannot be overwritten";
            LOGGER.error(data.loadError);
            return data;
        }
        data.revision = tag.getLong("Revision");
        ListTag settlements = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < settlements.size(); i++) {
            CompoundTag settlementTag = settlements.getCompound(i);
            int settlementSchema = settlementTag.getInt("Schema");
            if (settlementSchema != SettlementRecord.SCHEMA) {
                data.loadError = "Unsupported Ultima Kingdoms settlement schema " + settlementSchema
                        + " at record " + i
                        + "; refusing to initialize so the original save cannot be overwritten";
                LOGGER.error(data.loadError);
                data.settlements.clear();
                data.redirects.clear();
                return data;
            }
            try {
                SettlementRecord record = SettlementRecord.load(settlementTag);
                data.settlements.put(record.id, record);
            } catch (RuntimeException exception) {
                LOGGER.error("Skipping corrupt settlement record {}", i, exception);
            }
        }
        ListTag redirects = tag.getList("Redirects", Tag.TAG_COMPOUND);
        for (int i = 0; i < redirects.size(); i++) {
            CompoundTag redirect = redirects.getCompound(i);
            if (redirect.hasUUID("Source") && redirect.hasUUID("Target")) {
                data.redirects.put(redirect.getUUID("Source"), redirect.getUUID("Target"));
            }
        }
        return data;
    }

    private void validateLoaded() {
        if (loadError != null) throw new IllegalStateException(loadError);
    }
}
