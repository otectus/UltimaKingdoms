package com.ultimakingdoms.politics;

import java.util.Properties;

/** Build-expanded dependency contract; source of truth is gradle.properties. */
final class PoliticalProviderVersions {
    static final String TOWNSTEAD = load();
    private PoliticalProviderVersions() { }
    private static String load() {
        try (var stream = PoliticalProviderVersions.class.getResourceAsStream("/ultima_kingdoms-politics.properties")) {
            if (stream == null) throw new IllegalStateException("Missing political provider contract");
            Properties properties = new Properties(); properties.load(stream);
            String version = properties.getProperty("townstead.read.version");
            if (version == null || version.isBlank() || version.contains("${")) throw new IllegalStateException("Unexpanded political provider contract");
            return version;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read political provider contract", failure); }
    }
}
