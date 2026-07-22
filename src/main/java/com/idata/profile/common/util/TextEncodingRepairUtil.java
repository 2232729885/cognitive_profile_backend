package com.idata.profile.common.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class TextEncodingRepairUtil {

    private static final String[] UTF8_MOJIBAKE_MARKERS = {
            "Ã", "Â", "â", "æ", "å", "ç", "è", "é", "ä", "ã", "ï¼", "ã"
    };

    private TextEncodingRepairUtil() {
    }

    public static String repairLikelyUtf8Mojibake(String text) {
        if (!looksLikeUtf8Mojibake(text)) {
            return text;
        }
        try {
            String repaired = new String(text.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return hasText(repaired) ? repaired : text;
        } catch (Exception e) {
            return text;
        }
    }

    public static boolean looksLikeUtf8Mojibake(String text) {
        if (!hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int markerHits = 0;
        for (String marker : UTF8_MOJIBAKE_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                markerHits++;
            }
        }
        int c1Controls = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if ((codePoint >= 0x80 && codePoint <= 0x9F) || codePoint == 0xFFFD) {
                c1Controls++;
            }
        }
        return c1Controls > 0 || markerHits >= 2;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
