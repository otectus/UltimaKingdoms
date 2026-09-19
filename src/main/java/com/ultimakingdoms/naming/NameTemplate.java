package com.ultimakingdoms.naming;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record NameTemplate(String format, int weight) {
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z0-9_]+)}");

    public NameTemplate {
        if (Objects.requireNonNull(format, "format").isBlank()) {
            throw new IllegalArgumentException("Name template format must not be blank");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("Name template weight must be positive");
        }
    }

    public String generate(Map<String, List<String>> tokens, RandomGenerator random) {
        Matcher matcher = TOKEN.matcher(format);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            List<String> choices = tokens.get(matcher.group(1));
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException("Missing token set " + matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(choices.get(random.nextInt(choices.size()))));
        }
        matcher.appendTail(result);
        return result.toString().strip();
    }

    public void validateTokens(Map<String, List<String>> tokens) {
        Matcher matcher = TOKEN.matcher(format);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            if (!tokens.containsKey(matcher.group(1)) || tokens.get(matcher.group(1)).isEmpty()) {
                throw new IllegalArgumentException("Template references missing token set " + matcher.group(1));
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Template must contain at least one token placeholder");
        }
    }
}
