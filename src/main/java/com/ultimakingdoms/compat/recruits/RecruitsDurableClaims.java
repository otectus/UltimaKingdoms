package com.ultimakingdoms.compat.recruits;

import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Read-only confirmation of the audited 1.15.2 save format. Never writes or loads native state. */
public final class RecruitsDurableClaims {
    public record Claim(UUID id, String owner, boolean siege, Set<Long> chunks) {
        public Claim { chunks = Set.copyOf(chunks); }
    }
    public record Snapshot(boolean available, Map<UUID, Claim> claims) {
        public Snapshot { claims = Map.copyOf(claims); }
    }
    /** Parse once per provider file replacement/save; keep cache scoped to one server runtime. */
    public static final class Reader {
        private java.nio.file.attribute.BasicFileAttributes previous;
        private Snapshot cached = new Snapshot(false, Map.of());
        public Snapshot snapshot(MinecraftServer server) {
            if (!server.isSameThread()) throw new IllegalStateException("Native confirmation requires server thread");
            try {
                var path = server.getWorldPath(LevelResource.ROOT).resolve("data/recruitsClaims.dat");
                var current = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                if (previous == null || current.size() != previous.size() || !current.lastModifiedTime().equals(previous.lastModifiedTime())
                        || !Objects.equals(current.fileKey(), previous.fileKey())) {
                    cached = read(server); previous = current;
                }
                return cached;
            } catch (IOException failure) { previous = null; cached = new Snapshot(false, Map.of()); return cached; }
        }
    }
    public static Snapshot read(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Native confirmation requires server thread");
        var file = server.getWorldPath(LevelResource.ROOT).resolve("data/recruitsClaims.dat");
        try {
            if (Files.size(file) > 16_000_000) return new Snapshot(false, Map.of());
            CompoundTag root;
            try (var input = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
                root = NbtIo.read(input, new NbtAccounter(32_000_000L));
            }
            if (!root.contains("data", Tag.TAG_COMPOUND)) throw new IOException("Missing native data");
            var data = root.getCompound("data");
            if (!(data.get("claims") instanceof ListTag claims) || claims.size() > 10_000
                    || !claims.isEmpty() && claims.getElementType() != Tag.TAG_COMPOUND) throw new IOException("Unsupported claims");
            var result = new HashMap<UUID, Claim>();
            for (Tag value : claims) {
                var tag = (CompoundTag) value;
                UUID id = tag.getUUID("UUID");
                // Native getStringID() is serialized under the historical key "teamName".
                String owner = tag.getCompound("ownerFaction").getString("teamName");
                if (owner.isBlank() || owner.length() > 128 || !tag.contains("isUnderSiege", Tag.TAG_BYTE)
                        || !(tag.get("chunks") instanceof ListTag chunks) || chunks.size() > 10_000
                        || !chunks.isEmpty() && chunks.getElementType() != Tag.TAG_COMPOUND) throw new IOException("Malformed native claim");
                var positions = new HashSet<Long>();
                for (Tag chunk : chunks) {
                    var position = (CompoundTag) chunk;
                    if (!position.contains("x", Tag.TAG_INT) || !position.contains("z", Tag.TAG_INT)) throw new IOException("Malformed native chunk");
                    positions.add(net.minecraft.world.level.ChunkPos.asLong(position.getInt("x"), position.getInt("z")));
                }
                if (result.put(id, new Claim(id, owner, tag.getBoolean("isUnderSiege"), positions)) != null) throw new IOException("Duplicate native identity");
            }
            return new Snapshot(true, result);
        } catch (IOException | RuntimeException failure) { return new Snapshot(false, Map.of()); }
    }
    private RecruitsDurableClaims() { }
}
