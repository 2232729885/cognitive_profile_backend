package com.idata.profile.common.constant;

import java.util.Map;
import java.util.Set;

public final class AllowedEventTypes {

    public static final Set<String> VALUES = Set.of(
            "politics",
            "military",
            "economy",
            "society",
            "culture",
            "science_tech",
            "security",
            "nature",
            "other");

    private static final Map<String, String> LEGACY_ALIASES = Map.of(
            "election", "politics",
            "diplomatic", "politics",
            "protest", "politics",
            "disaster", "nature");

    private AllowedEventTypes() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        normalized = LEGACY_ALIASES.getOrDefault(normalized, normalized);
        return VALUES.contains(normalized) ? normalized : "other";
    }
}
