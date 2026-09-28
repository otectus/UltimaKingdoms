package com.ultimakingdoms.compat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No optional dependency may be pinned to one exact version.
 *
 * <p>Forge enforces an optional dependency's range whenever that mod is present, so an exact pin such as
 * {@code [1.15.2]} refuses to launch beside every other release of it, although each integration here
 * already probes the other mod and fails closed. 0.1.1 shipped Recruits pinned that way. Version checks
 * that an integration genuinely needs belong in its own runtime gate (as {@code RecruitsMixinPlugin} and
 * the Recruits adapters do), not in {@code mods.toml}.
 */
class ModsTomlRangesTest {

    private static final Pattern FIELD = Pattern.compile("(?m)^\\s*(modId|mandatory|versionRange)\\s*=\\s*\"?([^\"\\n]*)\"?\\s*$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z_]+)}");
    private static final Pattern EXACT = Pattern.compile("^\\[[^,\\]]+]$");

    @Test
    void noOptionalDependencyIsPinnedToOneVersion() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("gradle.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String toml = Files.readString(Path.of("src/main/resources/META-INF/mods.toml"), StandardCharsets.UTF_8);

        List<String> optional = new ArrayList<>();
        List<String> pinned = new ArrayList<>();
        // Tables start with "[[" and a version range only ever holds single brackets, so each chunk after
        // a "[[dependencies." marker is exactly one dependency table.
        for (String chunk : toml.split("\\[\\[")) {
            if (!chunk.startsWith("dependencies.")) {
                continue;
            }
            String modId = null;
            String range = null;
            boolean mandatory = true;
            Matcher field = FIELD.matcher(chunk.substring(chunk.indexOf("]]") + 2));
            while (field.find()) {
                switch (field.group(1)) {
                    case "modId" -> modId = field.group(2).trim();
                    case "mandatory" -> mandatory = Boolean.parseBoolean(field.group(2).trim());
                    case "versionRange" -> range = expand(field.group(2).trim(), properties);
                    default -> { }
                }
            }
            if (modId == null || mandatory) {
                continue;
            }
            optional.add(modId);
            if (range != null && EXACT.matcher(range).matches()) {
                pinned.add(modId + " " + range);
            }
        }

        assertTrue(optional.contains("recruits"), "Expected Recruits among the optional dependencies; the parse is wrong");
        assertFalse(optional.isEmpty());
        assertEquals(List.of(), pinned, "Optional dependencies must carry a range, not one exact version. Gate "
                + "an audited version at runtime inside the integration instead.");
    }

    /**
     * Ultima Kingdoms consumes every family companion, so it loads after each of them. A companion that
     * declared this mod AFTER as well would form a cycle Forge refuses to sort, and neither mod would
     * start: MCA: Crime 0.7.5's development line did exactly that until 2026-09-28. The companions'
     * own mods.toml tests pin their half (MCA: Crime declares ultima_kingdoms BEFORE).
     */
    @Test
    void everyFamilyCompanionLoadsBeforeThisMod() throws IOException {
        String toml = Files.readString(Path.of("src/main/resources/META-INF/mods.toml"), StandardCharsets.UTF_8);
        java.util.Map<String, String> ordering = new java.util.LinkedHashMap<>();
        for (String chunk : toml.split("\\[\\[")) {
            if (!chunk.startsWith("dependencies.")) {
                continue;
            }
            Matcher id = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(chunk);
            Matcher order = Pattern.compile("(?m)^\\s*ordering\\s*=\\s*\"([^\"]+)\"").matcher(chunk);
            if (id.find() && order.find()) {
                ordering.put(id.group(1), order.group(1));
            }
        }
        for (String companion : List.of("mca", "mcaquests", "mcacrime", "mcareputation", "townstead", "recruits")) {
            assertEquals("AFTER", ordering.get(companion), companion + " must load before Ultima Kingdoms");
        }
    }

    private static String expand(String value, Properties properties) {
        Matcher placeholder = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (placeholder.find()) {
            String replacement = properties.getProperty(placeholder.group(1));
            assertTrue(replacement != null, "mods.toml names ${" + placeholder.group(1) + "}, which gradle.properties lacks");
            placeholder.appendReplacement(out, Matcher.quoteReplacement(replacement.trim()));
        }
        placeholder.appendTail(out);
        return out.toString();
    }
}
