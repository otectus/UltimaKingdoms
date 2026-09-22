package com.ultimakingdoms.compat.recruits;

import com.ultimakingdoms.warfare.CampaignState.Relation;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;
import java.util.*;

/** Exact-provider adapter. Callers persist their political intent before invoking an authorized write. */
public final class RecruitsMilitary {
    public record Faction(String id, UUID leader) { }
    private static final String ROOT = "com.talhanation.recruits.";
    private record SavedRelations(java.nio.file.attribute.FileTime modified, long size, CompoundTag teams) { }
    private static final Map<MinecraftServer,SavedRelations> SAVED = new WeakHashMap<>();
    public static void clear(MinecraftServer server) { SAVED.remove(server); }
    private RecruitsMilitary() { }
    public static void ready(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalArgumentException("Native military operations require server thread.");
        if (!ModList.get().getModContainerById("recruits").map(m -> m.getModInfo().getVersion().toString().equals("1.15.2")).orElse(false))
            throw new IllegalArgumentException("Supported Recruits 1.15.2 provider unavailable.");
    }
    private static Object manager(String field) throws ReflectiveOperationException {
        return Objects.requireNonNull(Class.forName(ROOT + "FactionEvents").getField(field).get(null), "Native manager not ready");
    }
    public static Optional<Faction> faction(MinecraftServer server, String id) {
        ready(server);
        try {
            Object manager = manager("recruitsFactionManager");
            Object value = manager.getClass().getMethod("getFactionByStringID", String.class).invoke(manager, id);
            if (value == null) return Optional.empty();
            return Optional.of(new Faction((String)value.getClass().getMethod("getStringID").invoke(value),
                    (UUID)value.getClass().getMethod("getTeamLeaderUUID").invoke(value)));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { return Optional.empty(); }
    }
    public static boolean commands(ServerPlayer actor, String faction) {
        return actor != null && actor.getServer() != null && actor.getServer().isSameThread()
                && actor.level().dimension().equals(Level.OVERWORLD) && actor.getTeam() != null
                && actor.getTeam().getName().equals(faction)
                && faction(actor.getServer(), faction).map(f -> actor.getUUID().equals(f.leader())).orElse(false);
    }
    public static String commandedFaction(ServerPlayer actor) {
        if (actor.getTeam() == null || !commands(actor, actor.getTeam().getName()))
            throw new IllegalArgumentException("Only the native faction leader may command its military policy.");
        return actor.getTeam().getName();
    }
    public static Relation relation(MinecraftServer server, String from, String to) {
        ready(server);
        if (faction(server, from).isEmpty() || faction(server, to).isEmpty()) throw new IllegalArgumentException("Native faction unavailable.");
        try {
            Object manager = manager("recruitsDiplomacyManager");
            return Relation.valueOf(manager.getClass().getMethod("getRelation", String.class, String.class).invoke(manager, from, to).toString());
        } catch (ReflectiveOperationException | LinkageError failure) { throw new IllegalArgumentException("Native diplomacy API unavailable.", failure); }
    }
    public static boolean saved(MinecraftServer server, String from, String to, Relation expected) {
        ready(server);
        try {
            var path = server.getWorldPath(LevelResource.ROOT).resolve("data/diplomacy_data.dat");
            var attributes=java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
            if (attributes.size() > 8_000_000) return false;
            var cached=SAVED.get(server);
            if(cached==null||cached.size()!=attributes.size()||!cached.modified().equals(attributes.lastModifiedTime())) {
                CompoundTag root;
                try (var input = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(path)))) {
                    root = NbtIo.read(input, new NbtAccounter(16_000_000));
                }
                if (!root.contains("data", Tag.TAG_COMPOUND) || !root.getCompound("data").contains("teams", Tag.TAG_COMPOUND)) return false;
                cached=new SavedRelations(attributes.lastModifiedTime(),attributes.size(),root.getCompound("data").getCompound("teams"));SAVED.put(server,cached);
            }
            var sides=cached.teams();
            if (!sides.contains(from)) return expected == Relation.NEUTRAL;
            if (!sides.contains(from, Tag.TAG_COMPOUND)) return false;
            var side = sides.getCompound(from);
            if (!side.contains(to)) return expected == Relation.NEUTRAL;
            return side.contains(to, Tag.TAG_BYTE) && side.getByte(to) == expected.ordinal();
        } catch (java.io.IOException | RuntimeException failure) { return false; }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean apply(ServerPlayer actor, String from, String to, Relation before, Relation desired) {
        var server = actor.getServer(); ready(server);
        if (!packetGuardInstalled() || !RecruitsEvents.available()) throw new IllegalArgumentException("Verified native military security hooks unavailable.");
        if (!commands(actor, from) || from.equals(to)) throw new IllegalArgumentException("Native command authority unavailable.");
        Relation current = relation(server, from, to);
        if (current != before && current != desired) throw new IllegalArgumentException("Native relation changed; reconcile explicitly.");
        try {
            Object manager = manager("recruitsDiplomacyManager");
            if (current != desired) {
                Class<? extends Enum> type = (Class<? extends Enum>)Class.forName(ROOT + "world.RecruitsDiplomacyManager$DiplomacyStatus");
                Object status = Enum.valueOf(type, desired.name());
                manager.getClass().getMethod("setRelation", String.class, String.class, type, ServerLevel.class)
                        .invoke(manager, from, to, status, server.overworld());
            }
            if (relation(server, from, to) != desired) return false; // Provider event veto.
            manager.getClass().getMethod("save", ServerLevel.class).invoke(manager, server.overworld());
            var data = (SavedData)Class.forName(ROOT + "world.RecruitsDiplomacySaveData").getMethod("get", ServerLevel.class).invoke(null, server.overworld());
            data.save(server.getWorldPath(LevelResource.ROOT).resolve("data/diplomacy_data.dat").toFile());
            Object claims = Class.forName(ROOT + "ClaimEvents").getField("recruitsClaimManager").get(null);
            claims.getClass().getMethod("save", ServerLevel.class).invoke(claims, server.overworld());
            var claimData = (SavedData)Class.forName(ROOT + "world.RecruitsClaimSaveData").getMethod("get", ServerLevel.class).invoke(null, server.overworld());
            claimData.save(server.getWorldPath(LevelResource.ROOT).resolve("data/recruitsClaims.dat").toFile());
            SAVED.remove(server);
            return saved(server, from, to, desired);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { return false; }
    }
    public static boolean packetGuardInstalled() {
        try {
            return Arrays.stream(Class.forName(ROOT + "network.MessageChangeDiplomacyStatus").getDeclaredMethods())
                    .anyMatch(m -> m.getName().contains("ultima$authorize"))
                    && Arrays.stream(Class.forName(ROOT + "network.MessageAnswerTreaty").getDeclaredMethods())
                    .anyMatch(m -> m.getName().contains("ultima$authorizeTreaty"));
        } catch (ClassNotFoundException | LinkageError failure) { return false; }
    }
}
