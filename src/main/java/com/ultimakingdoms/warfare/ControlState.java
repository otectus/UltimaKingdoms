package com.ultimakingdoms.warfare;

import java.util.*;

/** Snapshot history, never a siege reward receipt or an annexation instruction. */
public final class ControlState {
    public static final int LIMIT = 1024, HISTORY_LIMIT = 32;
    public enum Condition { CONTROLLED, UNDER_SIEGE, OCCUPIED, CLAIM_REMOVED, UNMAPPED, RETIRED }
    public record Mapping(String nativeFaction, String kingdom) {
        public Mapping { token(nativeFaction); token(kingdom); }
    }
    public record Entry(long sequence, long gameTime, String owner, Condition condition, UUID administrator) {
        public Entry(long sequence, long gameTime, String owner, Condition condition) { this(sequence, gameTime, owner, condition, null); }
        public Entry {
            if (sequence < 1 || gameTime < 0) throw new IllegalArgumentException("Invalid control history");
            token(owner); Objects.requireNonNull(condition);
            if (condition == Condition.RETIRED && administrator == null) throw new IllegalArgumentException("Missing retirement authority");
        }
    }
    public record Binding(UUID settlement, UUID claim, int chunkX, int chunkZ, String sovereign,
                          String owner, Condition condition, long sequence, List<Entry> history) {
        public Binding {
            Objects.requireNonNull(settlement); Objects.requireNonNull(claim);
            token(sovereign); token(owner); Objects.requireNonNull(condition);
            history = List.copyOf(history);
            if (history.isEmpty() || history.size() > HISTORY_LIMIT || sequence < 1)
                throw new IllegalArgumentException("Invalid binding history");
            long previous = 0;
            for (var entry : history) {
                if (entry.sequence() <= previous || entry.sequence() > sequence) throw new IllegalArgumentException("Unordered history");
                previous = entry.sequence();
            }
            var last = history.get(history.size() - 1);
            if (last.sequence() != sequence || !last.owner().equals(owner) || last.condition() != condition)
                throw new IllegalArgumentException("Control history mismatch");
        }
        public Binding observe(String nextOwner, boolean siege, boolean exists, Map<String, Mapping> mappings, long time) {
            Condition next = !exists ? Condition.CLAIM_REMOVED : !mappings.containsKey(nextOwner) ? Condition.UNMAPPED
                    : siege ? Condition.UNDER_SIEGE : mappings.get(nextOwner).kingdom().equals(sovereign)
                    ? Condition.CONTROLLED : Condition.OCCUPIED;
            if (owner.equals(nextOwner) && condition == next) return this;
            var entries = new ArrayList<>(history);
            long nextSequence = Math.addExact(sequence, 1);
            entries.add(new Entry(nextSequence, time, nextOwner, next));
            if (entries.size() > HISTORY_LIMIT) entries.remove(0);
            return new Binding(settlement, claim, chunkX, chunkZ, sovereign, nextOwner, next, nextSequence, entries);
        }
        public Binding retire(long time, UUID administrator) {
            var entries = new ArrayList<>(history);
            long next = Math.addExact(sequence, 1);
            entries.add(new Entry(next, time, owner, Condition.RETIRED, administrator));
            if (entries.size() > HISTORY_LIMIT) entries.remove(0);
            return new Binding(settlement, claim, chunkX, chunkZ, sovereign, owner, Condition.RETIRED, next, entries);
        }
    }
    public long revision;
    public Map<String, Mapping> mappings = new TreeMap<>();
    public Map<UUID, Binding> bindings = new TreeMap<>();
    public Map<UUID, Binding> retired = new TreeMap<>();
    public void validate() {
        if (revision < 0 || mappings == null || bindings == null || retired == null || mappings.size() > LIMIT || bindings.size() + retired.size() > LIMIT)
            throw new IllegalArgumentException("Invalid control capacity");
        var claims = new HashSet<UUID>();
        mappings.forEach((key, value) -> {
            Objects.requireNonNull(value);
            if (!key.equals(value.nativeFaction())) throw new IllegalArgumentException("Mapping identity mismatch");
        });
        bindings.forEach((key, value) -> {
            Objects.requireNonNull(value);
            if (!key.equals(value.settlement()) || value.condition() == Condition.RETIRED || !claims.add(value.claim())) throw new IllegalArgumentException("Duplicate claim binding");
        });
        retired.forEach((key, value) -> {
            if (value == null || !key.equals(value.claim()) || value.condition() != Condition.RETIRED || !claims.add(key))
                throw new IllegalArgumentException("Invalid retired binding");
        });
    }
    static void token(String text) {
        if (text == null || text.isBlank() || text.length() > 128 || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid political identifier");
    }
}
