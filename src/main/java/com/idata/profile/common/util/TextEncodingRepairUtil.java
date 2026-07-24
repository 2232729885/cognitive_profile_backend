package com.idata.profile.common.util;

import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
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
        String best = text;
        int bestScore = mojibakeScore(text);
        best = betterRepair(text, best, bestScore, StandardCharsets.ISO_8859_1);
        bestScore = mojibakeScore(best);
        best = betterRepair(text, best, bestScore, Charset.forName("windows-1252"));
        return hasText(best) ? best : text;
    }

    private static String betterRepair(String original, String currentBest, int currentBestScore, Charset sourceCharset) {
        try {
            String repaired = new String(original.getBytes(sourceCharset), StandardCharsets.UTF_8);
            int repairedScore = mojibakeScore(repaired);
            return hasText(repaired) && repairedScore < currentBestScore ? repaired : currentBest;
        } catch (Exception e) {
            return currentBest;
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
        int latinUtf8LeadBytes = 0;
        int c1Controls = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == 0x00C2 || codePoint == 0x00C3
                    || codePoint == 0x00C4 || codePoint == 0x00C5
                    || codePoint == 0x00C6 || codePoint == 0x00C7
                    || codePoint == 0x00C8 || codePoint == 0x00C9
                    || codePoint == 0x00E2 || codePoint == 0x00E3
                    || codePoint == 0x00E4 || codePoint == 0x00E5
                    || codePoint == 0x00E6 || codePoint == 0x00E7
                    || codePoint == 0x00E8 || codePoint == 0x00E9) {
                latinUtf8LeadBytes++;
            }
            if ((codePoint >= 0x80 && codePoint <= 0x9F) || codePoint == 0xFFFD) {
                c1Controls++;
            }
        }
        return c1Controls > 0 || markerHits >= 2 || latinUtf8LeadBytes >= 3;
    }

    private static int mojibakeScore(String text) {
        if (!hasText(text)) {
            return Integer.MAX_VALUE;
        }
        int score = 0;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : UTF8_MOJIBAKE_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                score += 4;
            }
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if ((codePoint >= 0x80 && codePoint <= 0x9F) || codePoint == 0xFFFD) {
                score += 10;
            }
            if (codePoint == 0x00C2 || codePoint == 0x00C3
                    || codePoint == 0x00E2 || codePoint == 0x00E3) {
                score += 3;
            }
        }
        return score;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
