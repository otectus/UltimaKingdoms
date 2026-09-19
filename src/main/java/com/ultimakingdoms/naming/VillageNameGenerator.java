package com.ultimakingdoms.naming;

import com.ultimakingdoms.api.GeneratedName;
import com.ultimakingdoms.api.SettlementCandidate;
import net.minecraft.resources.ResourceLocation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Predicate;

public final class VillageNameGenerator {
    private VillageNameGenerator() {
    }

    public static GeneratedName generate(
            long worldSeed,
            SettlementCandidate candidate,
            ResourceLocation kingdomId,
            NamePool pool,
            Predicate<String> nameTaken,
            Predicate<ResourceLocation> slugTaken
    ) {
        long seed = seed(worldSeed, candidate, kingdomId);
        SplittableRandom random = new SplittableRandom(seed);
        List<CandidateName> choices = new ArrayList<>();
        candidate.proposedName().ifPresent(name -> choices.add(new CandidateName(name, Optional.of("proposed"))));

        int start = random.nextInt(pool.canonical().size());
        for (int offset = 0; offset < pool.canonical().size(); offset++) {
            choices.add(new CandidateName(pool.canonical().get((start + offset) % pool.canonical().size()), Optional.empty()));
        }
        for (int attempt = 0; attempt < pool.retries(); attempt++) {
            NameTemplate template = choose(pool.templates(), random);
            choices.add(new CandidateName(template.generate(pool.tokens(), random), Optional.of(template.format())));
        }

        for (CandidateName choice : choices) {
            String normalized = normalize(choice.displayName());
            if (normalized.isEmpty() || pool.reserved().contains(normalized) || pool.blacklist().contains(normalized)
                    || nameTaken.test(normalized)) {
                continue;
            }
            return create(choice.displayName(), normalized, pool.id(), choice.recipe(), seed, slugTaken);
        }

        String stem = titleFromId(kingdomId) + " " + Long.toUnsignedString(mix(seed), 36).toUpperCase(Locale.ROOT);
        String normalized = normalize(stem);
        int disambiguator = 0;
        while (nameTaken.test(normalized) && disambiguator < 256) {
            disambiguator++;
            stem = titleFromId(kingdomId) + " " + Long.toUnsignedString(mix(seed + disambiguator), 36).toUpperCase(Locale.ROOT);
            normalized = normalize(stem);
        }
        return create(stem, normalized, pool.id(), Optional.of("deterministic_fallback"), seed, slugTaken);
    }

    public static String normalize(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
        StringBuilder normalized = new StringBuilder(decomposed.length());
        decomposed.codePoints()
                .filter(codePoint -> Character.getType(codePoint) != Character.NON_SPACING_MARK)
                .map(Character::toLowerCase)
                .filter(Character::isLetterOrDigit)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private static GeneratedName create(String display, String normalized, ResourceLocation poolId,
                                        Optional<String> recipe, long seed,
                                        Predicate<ResourceLocation> slugTaken) {
        String basePath = slugPath(display);
        ResourceLocation slug = new ResourceLocation("ultima_kingdoms", basePath);
        int attempt = 0;
        while (slugTaken.test(slug) && attempt < 256) {
            attempt++;
            String suffix = Long.toUnsignedString(mix(seed + attempt), 36);
            slug = new ResourceLocation("ultima_kingdoms", basePath + "-" + suffix.substring(0, Math.min(8, suffix.length())));
        }
        if (slugTaken.test(slug)) {
            String suffix = String.format(Locale.ROOT, "%016x%016x", mix(seed), mix(~seed));
            slug = new ResourceLocation("ultima_kingdoms", basePath + "-" + suffix);
        }
        return new GeneratedName(display, normalized, slug, poolId, recipe);
    }

    private static NameTemplate choose(List<NameTemplate> templates, SplittableRandom random) {
        long total = templates.stream().mapToLong(NameTemplate::weight).sum();
        long roll = random.nextLong(total);
        for (NameTemplate template : templates) {
            roll -= template.weight();
            if (roll < 0) {
                return template;
            }
        }
        return templates.get(templates.size() - 1);
    }

    private static String slugPath(String display) {
        String ascii = Normalizer.normalize(display, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return ascii.isEmpty() ? "settlement" : ascii;
    }

    private static String titleFromId(ResourceLocation id) {
        String value = id.getPath().replace('_', ' ').replace('-', ' ').strip();
        return value.isEmpty() ? "Settlement" : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static long seed(long worldSeed, SettlementCandidate candidate, ResourceLocation kingdomId) {
        long value = mix(worldSeed ^ candidate.dimension().location().toString().hashCode());
        value = mix(value ^ Math.floorDiv(candidate.anchor().getX(), 16));
        value = mix(value ^ ((long) Math.floorDiv(candidate.anchor().getZ(), 16) << 32));
        return mix(value ^ kingdomId.toString().hashCode());
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    private record CandidateName(String displayName, Optional<String> recipe) {
    }
}
