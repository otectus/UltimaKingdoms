package com.ultimakingdoms.data;

import com.ultimakingdoms.api.AssignmentSource;
import com.ultimakingdoms.api.GeneratedName;
import com.ultimakingdoms.api.KingdomResolution;
import com.ultimakingdoms.api.KingdomView;
import com.ultimakingdoms.api.SettlementCandidate;
import com.ultimakingdoms.kingdom.BiomeRule;
import com.ultimakingdoms.kingdom.KingdomDefinition;
import com.ultimakingdoms.kingdom.StructureStyleRule;
import com.ultimakingdoms.naming.NamePool;
import com.ultimakingdoms.naming.VillageNameGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public final class DefinitionSnapshot {
    private static final double CENTER_WEIGHT = 0.60;
    private static final double AREA_WEIGHT = 0.30;
    private static final Comparator<RuleMatch> RULE_ORDER = Comparator
            .comparing((RuleMatch match) -> match.match() == BiomeRule.Match.EXACT).reversed()
            .thenComparing(Comparator.comparingInt((RuleMatch match) -> match.rule().priority()).reversed())
            .thenComparing(match -> match.rule().id().toString());
    private static final Comparator<StructureStyleRule> STYLE_ORDER = Comparator
            .comparingInt(StructureStyleRule::priority).reversed()
            .thenComparing(rule -> rule.id().toString());

    private final Map<ResourceLocation, KingdomDefinition> kingdoms;
    private final Map<ResourceLocation, NamePool> namePools;
    private final List<BiomeRule> biomeRules;
    private final List<StructureStyleRule> styleRules;
    private final ResourceLocation fallbackKingdom;
    private final long revision;

    DefinitionSnapshot(Map<ResourceLocation, KingdomDefinition> kingdoms,
                       Map<ResourceLocation, NamePool> namePools,
                       List<BiomeRule> biomeRules,
                       List<StructureStyleRule> styleRules,
                       ResourceLocation fallbackKingdom,
                       long revision) {
        this.kingdoms = Map.copyOf(kingdoms);
        this.namePools = Map.copyOf(namePools);
        this.biomeRules = List.copyOf(biomeRules);
        this.styleRules = List.copyOf(styleRules);
        this.fallbackKingdom = fallbackKingdom;
        this.revision = revision;
    }

    static DefinitionSnapshot empty() {
        return new DefinitionSnapshot(Map.of(), Map.of(), List.of(), List.of(), null, 0L);
    }

    DefinitionSnapshot withRevision(long revision) {
        return new DefinitionSnapshot(kingdoms, namePools, biomeRules, styleRules, fallbackKingdom, revision);
    }

    public Optional<KingdomView> kingdom(ResourceLocation id) {
        return Optional.ofNullable(kingdoms.get(id)).map(definition -> definition);
    }

    public Collection<KingdomView> kingdoms() {
        return List.copyOf(kingdoms.values());
    }

    public long revision() {
        return revision;
    }

    public KingdomResolution resolve(ServerLevel level, SettlementCandidate candidate) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(candidate, "candidate");
        ensureLoaded();
        List<String> trace = new ArrayList<>();

        Optional<ResourceLocation> explicit = candidate.explicitKingdom();
        if (explicit.isPresent()) {
            if (kingdoms.containsKey(explicit.get())) {
                AssignmentSource source = candidate.detectionSource() == com.ultimakingdoms.api.DetectionSource.MANUAL
                        ? AssignmentSource.MANUAL : AssignmentSource.ADDON;
                trace.add("explicit " + explicit.get() + " from " + candidate.sourceId() + " (" + source + ")");
                return new KingdomResolution(explicit.get(), source, Optional.empty(), Integer.MAX_VALUE, 1.0, trace);
            }
            trace.add("ignored undefined explicit kingdom " + explicit.get());
        }

        List<WeightedBiome> samples = sample(level, candidate);
        Optional<KingdomResolution> primary = resolveBiomeTier(samples, false, trace);
        if (primary.isPresent()) {
            return primary.get();
        }

        Optional<KingdomResolution> style = resolveStyle(candidate, trace);
        if (style.isPresent()) {
            return style.get();
        }

        Optional<KingdomResolution> environmental = resolveBiomeTier(samples, true, trace);
        if (environmental.isPresent()) {
            return environmental.get();
        }

        ResourceLocation centerBiome = samples.get(0).id();
        trace.add("no biome or structure-style rule matched; configured fallback " + fallbackKingdom);
        return new KingdomResolution(fallbackKingdom, AssignmentSource.FALLBACK,
                Optional.of(centerBiome), Integer.MIN_VALUE, 0.0, trace);
    }

    public GeneratedName generateName(long worldSeed, SettlementCandidate candidate, ResourceLocation kingdomId,
                                      Predicate<String> normalizedNameTaken,
                                      Predicate<ResourceLocation> slugTaken) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(kingdomId, "kingdomId");
        Objects.requireNonNull(normalizedNameTaken, "normalizedNameTaken");
        Objects.requireNonNull(slugTaken, "slugTaken");
        ensureLoaded();
        KingdomDefinition kingdom = kingdoms.get(kingdomId);
        if (kingdom == null) {
            throw new IllegalArgumentException("Undefined kingdom " + kingdomId);
        }
        NamePool pool = namePools.get(kingdom.namePool());
        if (pool == null) {
            throw new IllegalStateException("Missing validated name pool " + kingdom.namePool());
        }
        return VillageNameGenerator.generate(worldSeed, candidate, kingdomId, pool,
                normalizedNameTaken, slugTaken);
    }

    private Optional<KingdomResolution> resolveBiomeTier(List<WeightedBiome> samples, boolean environmental,
                                                          List<String> trace) {
        List<SampleWinner> winners = new ArrayList<>();
        for (WeightedBiome sample : samples) {
            Optional<RuleMatch> winner = biomeRules.stream()
                    .filter(rule -> rule.environmental() == environmental)
                    .map(rule -> new RuleMatch(rule, rule.match(sample.biome())))
                    .filter(match -> match.match() != BiomeRule.Match.NONE)
                    .min(RULE_ORDER);
            winner.ifPresent(match -> {
                winners.add(new SampleWinner(sample, match));
                trace.add((environmental ? "environmental" : "biome") + " sample " + sample.label()
                        + " " + sample.id() + " matched " + match.rule().id() + " as "
                        + match.match().name().toLowerCase(java.util.Locale.ROOT) + " priority "
                        + match.rule().priority());
            });
        }
        if (winners.isEmpty()) {
            trace.add((environmental ? "environmental" : "primary biome") + " tier had no match");
            return Optional.empty();
        }

        boolean hasExact = winners.stream().anyMatch(winner -> winner.match().match() == BiomeRule.Match.EXACT);
        Map<ResourceLocation, Aggregate> aggregates = new LinkedHashMap<>();
        winners.stream()
                .filter(winner -> !hasExact || winner.match().match() == BiomeRule.Match.EXACT)
                .forEach(winner -> aggregates.computeIfAbsent(winner.match().rule().kingdomId(), ignored -> new Aggregate())
                        .add(winner));
        Map.Entry<ResourceLocation, Aggregate> winning = aggregates.entrySet().stream()
                .min(Comparator.<Map.Entry<ResourceLocation, Aggregate>>comparingDouble(entry -> entry.getValue().weight)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(
                                (Map.Entry<ResourceLocation, Aggregate> entry) -> entry.getValue().priority).reversed())
                        .thenComparing(entry -> entry.getKey().toString()))
                .orElseThrow();
        Aggregate result = winning.getValue();
        AssignmentSource source = environmental ? AssignmentSource.ENVIRONMENTAL
                : (hasExact ? AssignmentSource.EXACT_BIOME : AssignmentSource.BIOME_TAG);
        trace.add("selected " + winning.getKey() + " with weighted coverage "
                + String.format(java.util.Locale.ROOT, "%.3f", result.weight)
                + (hasExact ? " after exact-selector precedence" : " from tag selectors"));
        return Optional.of(new KingdomResolution(winning.getKey(), source,
                Optional.of(result.decisiveBiome), result.priority,
                Math.min(1.0, result.weight / (CENTER_WEIGHT + AREA_WEIGHT)), trace));
    }

    private Optional<KingdomResolution> resolveStyle(SettlementCandidate candidate, List<String> trace) {
        if (candidate.styleId().isEmpty()) {
            trace.add("candidate supplied no structure style");
            return Optional.empty();
        }
        ResourceLocation style = candidate.styleId().get();
        Optional<StructureStyleRule> explicitRule = styleRules.stream()
                .filter(rule -> rule.styles().contains(style))
                .min(STYLE_ORDER);
        if (explicitRule.isPresent()) {
            StructureStyleRule rule = explicitRule.get();
            trace.add("structure style " + style + " matched " + rule.id() + " priority " + rule.priority());
            return Optional.of(new KingdomResolution(rule.kingdomId(), AssignmentSource.STRUCTURE_STYLE,
                    Optional.empty(), rule.priority(), 1.0, trace));
        }
        Optional<KingdomDefinition> hint = kingdoms.values().stream()
                .filter(kingdom -> kingdom.styleHints().contains(style))
                .min(Comparator.comparing(kingdom -> kingdom.id().toString()));
        if (hint.isPresent()) {
            trace.add("structure style " + style + " matched kingdom style hint " + hint.get().id());
            return Optional.of(new KingdomResolution(hint.get().id(), AssignmentSource.STRUCTURE_STYLE,
                    Optional.empty(), 0, 1.0, trace));
        }
        trace.add("structure style " + style + " had no match");
        return Optional.empty();
    }

    private static List<WeightedBiome> sample(ServerLevel level, SettlementCandidate candidate) {
        BlockPos center = candidate.anchor();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int y = Math.max(minY, Math.min(maxY, center.getY()));
        LinkedHashMap<Long, BlockPos> area = new LinkedHashMap<>();
        int minX = candidate.bounds().minX();
        int maxX = candidate.bounds().maxX();
        int minZ = candidate.bounds().minZ();
        int maxZ = candidate.bounds().maxZ();
        add(area, new BlockPos(minX, y, minZ), center);
        add(area, new BlockPos(maxX, y, minZ), center);
        add(area, new BlockPos(minX, y, maxZ), center);
        add(area, new BlockPos(maxX, y, maxZ), center);
        add(area, new BlockPos(minX, y, center.getZ()), center);
        add(area, new BlockPos(maxX, y, center.getZ()), center);
        add(area, new BlockPos(center.getX(), y, minZ), center);
        add(area, new BlockPos(center.getX(), y, maxZ), center);

        List<WeightedBiome> samples = new ArrayList<>();
        double centerWeight = area.isEmpty() ? CENTER_WEIGHT + AREA_WEIGHT : CENTER_WEIGHT;
        samples.add(weighted(level, new BlockPos(center.getX(), y, center.getZ()), centerWeight, "center"));
        if (!area.isEmpty()) {
            double weight = AREA_WEIGHT / area.size();
            int index = 0;
            for (BlockPos pos : area.values()) {
                samples.add(weighted(level, pos, weight, "area[" + index++ + "]"));
            }
        }
        return samples;
    }

    private static void add(Map<Long, BlockPos> positions, BlockPos position, BlockPos center) {
        if (position.getX() != center.getX() || position.getZ() != center.getZ()) {
            positions.putIfAbsent(position.asLong(), position);
        }
    }

    private static WeightedBiome weighted(ServerLevel level, BlockPos pos, double weight, String label) {
        Holder<Biome> biome = level.getBiome(pos);
        ResourceLocation id = biome.unwrapKey().map(ResourceKey::location)
                .orElseThrow(() -> new IllegalStateException("Unregistered biome at " + pos));
        return new WeightedBiome(biome, id, weight, label);
    }

    private void ensureLoaded() {
        if (fallbackKingdom == null) {
            throw new IllegalStateException("Kingdom definitions have not been committed");
        }
    }

    private record WeightedBiome(Holder<Biome> biome, ResourceLocation id, double weight, String label) {
    }

    private record RuleMatch(BiomeRule rule, BiomeRule.Match match) {
    }

    private record SampleWinner(WeightedBiome sample, RuleMatch match) {
    }

    private static final class Aggregate {
        private double weight;
        private int priority = Integer.MIN_VALUE;
        private ResourceLocation decisiveBiome;
        private double decisiveWeight = -1.0;

        private void add(SampleWinner winner) {
            weight += winner.sample().weight();
            priority = Math.max(priority, winner.match().rule().priority());
            if (winner.sample().weight() > decisiveWeight) {
                decisiveWeight = winner.sample().weight();
                decisiveBiome = winner.sample().id();
            }
        }
    }
}
