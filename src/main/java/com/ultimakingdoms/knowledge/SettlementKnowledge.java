package com.ultimakingdoms.knowledge;

import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.SettlementView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/** Sparse discovery records. Existing globally published settlements stay public on first adoption. */
public final class SettlementKnowledge extends SavedData {
    public static final String NAME = "ultima_kingdoms_knowledge";
    private static final int LIMIT = 100_000;
    private final Set<UUID> legacyPublic = new HashSet<>();
    private final Map<UUID, Set<UUID>> discovered = new HashMap<>();
    private int entries;
    private boolean initialized;
    private long revision;
    private CompoundTag preserved;
    private String diagnostic = "";

    public static SettlementKnowledge get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Discovery requires the server thread");
        return server.overworld().getDataStorage().computeIfAbsent(SettlementKnowledge::load,
                SettlementKnowledge::new, NAME);
    }

    /** Called once before discovery resumes. Never visits chunks or rewrites civic records. */
    public void adopt(MinecraftServer server, KingdomsService kingdoms) {
        if (initialized || !writable()) return;
        Set<UUID> existing = new HashSet<>();
        kingdoms.getKingdoms().forEach(k -> kingdoms.getSettlements(k.id()).forEach(s -> existing.add(s.id())));
        if (existing.size() > LIMIT) throw new IllegalStateException("Legacy settlement discovery exceeds capacity");
        legacyPublic.addAll(existing);
        initialized = true;
        revision++;
        setDirty();
        save(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data").resolve(NAME + ".dat").toFile());
        if (isDirty()) throw new IllegalStateException("Cannot durably initialize settlement discovery");
    }

    public boolean writable() { return preserved == null; }
    public String diagnostic() { return diagnostic; }
    public long revision() { return revision; }
    public boolean knows(UUID player, UUID settlement) {
        return writable() && (legacyPublic.contains(settlement)
                || discovered.getOrDefault(player, Set.of()).contains(settlement));
    }

    public boolean discover(UUID player, UUID settlement) {
        Objects.requireNonNull(player); Objects.requireNonNull(settlement);
        if (!initialized || !writable() || knows(player, settlement)) return false;
        if (entries >= LIMIT || (!discovered.containsKey(player) && discovered.size() >= LIMIT)) return false;
        Set<UUID> known = discovered.computeIfAbsent(player, ignored -> new HashSet<>());
        if (known.size() >= LIMIT || !known.add(settlement)) return false;
        entries++;
        revision++;
        setDirty();
        return true;
    }

    public boolean visible(ServerPlayer viewer, UUID settlement) {
        return viewer.hasPermissions(2) || knows(viewer.getUUID(), settlement);
    }

    public List<SettlementView> page(ServerPlayer viewer, KingdomsService kingdoms,
                                     Optional<ResourceLocation> kingdom, int offset, int limit) {
        if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 64)
            throw new IllegalArgumentException("Invalid discovery page");
        if (viewer.hasPermissions(2)) return kingdoms.getSettlementPage(kingdom, offset, limit);
        Set<UUID> ids = new HashSet<>(legacyPublic);
        ids.addAll(discovered.getOrDefault(viewer.getUUID(), Set.of()));
        if (!writable()) ids.clear();
        return ids.stream().map(kingdoms::getSettlement).flatMap(Optional::stream)
                .filter(s -> kingdom.isEmpty() || kingdom.get().equals(s.kingdomId()))
                .sorted(Comparator.comparing(SettlementView::displayName).thenComparing(s -> s.id().toString()))
                .skip(offset).limit(limit).toList();
    }

    public long count(ServerPlayer viewer, KingdomsService kingdoms, ResourceLocation kingdom) {
        if (viewer.hasPermissions(2)) return kingdoms.getSettlements(kingdom).size();
        Set<UUID> ids = new HashSet<>(legacyPublic);
        ids.addAll(discovered.getOrDefault(viewer.getUUID(), Set.of()));
        if (!writable()) return 0;
        return ids.stream().map(kingdoms::getSettlement).flatMap(Optional::stream)
                .filter(s -> s.kingdomId().equals(kingdom)).count();
    }

    public Optional<SettlementView> find(ServerPlayer viewer, KingdomsService kingdoms, String query) {
        if (viewer.hasPermissions(2)) return kingdoms.findSettlement(query);
        try { return kingdoms.getSettlement(UUID.fromString(query)).filter(s -> visible(viewer, s.id())); }
        catch (IllegalArgumentException ignored) { }
        Set<UUID> ids = new HashSet<>(legacyPublic);
        ids.addAll(discovered.getOrDefault(viewer.getUUID(), Set.of()));
        if (!writable()) return Optional.empty();
        String slug = query.contains(":") ? query : "ultima_kingdoms:" + query.toLowerCase(Locale.ROOT);
        List<SettlementView> matches = ids.stream().map(kingdoms::getSettlement).flatMap(Optional::stream)
                .filter(s -> s.displayName().equalsIgnoreCase(query) || s.slug().toString().equals(slug)
                        || s.aliases().contains(query) || s.aliases().contains(slug)).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public void merge(UUID source, UUID target) {
        if (!writable()) return;
        boolean changed = false;
        if (legacyPublic.remove(source)) { legacyPublic.add(target); changed = true; }
        for (Set<UUID> ids : discovered.values()) {
            if (ids.remove(source)) { if (!ids.add(target)) entries--; changed = true; }
        }
        if (changed) { revision++; setDirty(); }
    }

    public static SettlementKnowledge load(CompoundTag tag) {
        SettlementKnowledge data = new SettlementKnowledge();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1
                    || !tag.contains("Initialized", Tag.TAG_BYTE) || !tag.contains("Revision", Tag.TAG_LONG)
                    || !tag.contains("Public", Tag.TAG_LIST) || !tag.contains("Players", Tag.TAG_LIST))
                throw new IllegalArgumentException("Unsupported discovery data");
            data.initialized = tag.getBoolean("Initialized"); data.revision = tag.getLong("Revision");
            if (data.revision < 0) throw new IllegalArgumentException("Negative discovery revision");
            readIds(compoundList(tag, "Public"), data.legacyPublic);
            ListTag players = compoundList(tag, "Players");
            if (players.size() > LIMIT) throw new IllegalArgumentException("Discovery player capacity exceeded");
            for (Tag entry : players) {
                CompoundTag player = (CompoundTag) entry;
                UUID id = player.getUUID("Player"); Set<UUID> known = new HashSet<>();
                if (!player.contains("Known", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing discoveries");
                readIds(compoundList(player, "Known"), known);
                data.entries += known.size();
                if (data.entries > LIMIT) throw new IllegalArgumentException("Total discovery capacity exceeded");
                if (data.discovered.putIfAbsent(id, known) != null) throw new IllegalArgumentException("Duplicate discovery player");
            }
        } catch (RuntimeException failure) {
            data.legacyPublic.clear(); data.discovered.clear(); data.preserved = tag.copy();
            data.diagnostic = "Settlement discovery is read-only: " + failure.getMessage();
            com.mojang.logging.LogUtils.getLogger().warn("{}; preserving {}.dat without player location disclosure", data.diagnostic, NAME);
        }
        return data;
    }

    private static ListTag compoundList(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid discovery list: " + key);
        return list;
    }

    private static void readIds(ListTag list, Set<UUID> target) {
        if (list.size() > LIMIT) throw new IllegalArgumentException("Discovery capacity exceeded");
        for (Tag entry : list) if (!target.add(((CompoundTag) entry).getUUID("Id")))
            throw new IllegalArgumentException("Duplicate discovery");
    }

    private static ListTag ids(Set<UUID> values) {
        ListTag list = new ListTag();
        values.stream().sorted().forEach(id -> { CompoundTag tag = new CompoundTag(); tag.putUUID("Id", id); list.add(tag); });
        return list;
    }

    @Override public void save(java.io.File destination) {
        if (!isDirty()) return;
        try {
            com.ultimakingdoms.persistence.AtomicSavedDataWriter.write(destination, save(new CompoundTag()));
            setDirty(false);
        } catch (java.io.IOException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Could not save settlement discovery; data remains dirty", failure);
        }
    }

    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) {
        if (!writable()) return preserved.copy();
        tag.putInt("Schema", 1); tag.putBoolean("Initialized", initialized); tag.putLong("Revision", revision);
        tag.put("Public", ids(legacyPublic)); ListTag players = new ListTag();
        discovered.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag player = new CompoundTag(); player.putUUID("Player", entry.getKey());
            player.put("Known", ids(entry.getValue())); players.add(player);
        });
        tag.put("Players", players); return tag;
    }
}
