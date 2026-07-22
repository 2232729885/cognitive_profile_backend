package com.idata.profile.common.util;

import java.util.Locale;

public final class LanguageCodeUtil {

    private LanguageCodeUtil() {
    }

    public static String normalizeForT1(String language) {
        if (!hasText(language)) {
            return "unknown";
        }
        String value = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (value.startsWith("zh")) {
            return "zh";
        }
        if (value.startsWith("en")) {
            return "en";
        }
        if (value.startsWith("ja") || value.startsWith("jp")) {
            return "ja";
        }
        if (value.startsWith("ko") || value.startsWith("kr")) {
            return "ko";
        }
        if (value.startsWith("vi")) {
            return "vi";
        }
        if (value.startsWith("tl") || value.startsWith("fil")) {
            return "tl";
        }
        if ("mixed".equals(value) || "unknown".equals(value)) {
            return value;
        }
        return "unknown";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
