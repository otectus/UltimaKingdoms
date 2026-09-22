package dev.otectus.mcaquests.compat.mapatlases;

import dev.otectus.mcaquests.compat.WaypointSpec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.*;

/** Automatic markers only. Frames retain immutable snapshots, never live mutable state. */
public final class AtlasMarkerStore {
    public static final Comparator<WaypointSpec> ORDER = Comparator
            .comparing((WaypointSpec s) -> !s.presentation().primary()).thenComparing(WaypointSpec::key);
    private final Map<String, WaypointSpec> markers = new HashMap<>();
    private final Map<String, Map<String, WaypointSpec>> external = new HashMap<>();
    private long epoch, revision;
    private volatile Snapshot snapshot = new Snapshot(0, 0, List.of(), Map.of());
    public record Bucket(ResourceKey<Level> dimension, int x, int z) { }
    public record Snapshot(long epoch, long revision, List<WaypointSpec> all,
                           Map<Bucket, List<WaypointSpec>> buckets) {
        public Snapshot {
            all=List.copyOf(all);Map<Bucket,List<WaypointSpec>> copy=new HashMap<>();
            for(var entry:buckets.entrySet())copy.put(entry.getKey(),List.copyOf(entry.getValue()));buckets=Map.copyOf(copy);
        }
        public List<WaypointSpec> near(ResourceKey<Level> dim, int cx, int cz, int scale) {
            // Small halo permits a validated loaded giver to cross a map/bucket seam between snapshots.
            int half = (64 << scale) + 64;
            List<WaypointSpec> result = new ArrayList<>();
            for (int x = Math.floorDiv(cx - half, 2048); x <= Math.floorDiv(cx + half, 2048); x++)
                for (int z = Math.floorDiv(cz - half, 2048); z <= Math.floorDiv(cz + half, 2048); z++)
                    result.addAll(buckets.getOrDefault(new Bucket(dim, x, z), List.of()));
            result.sort(ORDER);
            return result;
        }
    }
    public Snapshot snapshot() { return snapshot; }
    public synchronized boolean put(WaypointSpec spec) {
        if (spec.ownership() != WaypointSpec.Ownership.AUTOMATIC)
            throw new IllegalArgumentException("Personal pins do not belong in the overlay");
        if (spec.equals(markers.get(spec.key()))) return false;
        markers.put(spec.key(), spec); publish(); return true;
    }
    public synchronized boolean remove(String key) {
        if (markers.remove(key) == null) return false;
        publish(); return true;
    }
    public synchronized void clear() { markers.clear(); external.clear(); epoch++; publish(); }
    public synchronized Set<String> keys() { return Set.copyOf(markers.keySet()); }
    public synchronized void replaceExternal(String owner, Collection<WaypointSpec> points) {
        Map<String,WaypointSpec> replacement=new HashMap<>();for(WaypointSpec point:points){
            if(point.ownership()!=WaypointSpec.Ownership.AUTOMATIC||replacement.putIfAbsent(point.key(),point)!=null)
                throw new IllegalArgumentException("invalid external atlas snapshot");}
        if(replacement.equals(external.get(owner)))return;if(replacement.isEmpty())external.remove(owner);else external.put(owner,Map.copyOf(replacement));publish();
    }
    private void publish() {
        List<WaypointSpec> all = new ArrayList<>(markers.values());for (var values : external.values()) all.addAll(values.values()); all.sort(ORDER);
        Map<Bucket, List<WaypointSpec>> buckets = new HashMap<>();
        for (WaypointSpec spec : all) buckets.computeIfAbsent(new Bucket(spec.dimension(),
                Math.floorDiv(spec.pos().getX(), 2048), Math.floorDiv(spec.pos().getZ(), 2048)),
                ignored -> new ArrayList<>()).add(spec);
        buckets.replaceAll((key, values) -> List.copyOf(values));
        snapshot = new Snapshot(epoch, ++revision, all, buckets);
    }
}
