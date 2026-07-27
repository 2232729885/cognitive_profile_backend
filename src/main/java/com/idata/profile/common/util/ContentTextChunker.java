package com.idata.profile.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long content into overlapping, sentence-aware windows.
 *
 * <p>The embedding endpoint does not expose its tokenizer, so token counts are
 * estimated conservatively: CJK characters and punctuation count as one token,
 * while Latin/digit runs count as roughly one token per four characters.</p>
 */
@Component
public class ContentTextChunker {

    private static final int MIN_TARGET_TOKENS = 400;
    private static final int MAX_TARGET_TOKENS = 800;

    private final int thresholdTokens;
    private final int targetTokens;
    private final int overlapTokens;

    public ContentTextChunker(
            @Value("${pipeline.t4.chunking.threshold-tokens:800}") int thresholdTokens,
            @Value("${pipeline.t4.chunking.target-tokens:600}") int targetTokens,
            @Value("${pipeline.t4.chunking.overlap-tokens:75}") int overlapTokens) {
        this.thresholdTokens = Math.max(1, thresholdTokens);
        this.targetTokens = Math.max(MIN_TARGET_TOKENS, Math.min(MAX_TARGET_TOKENS, targetTokens));
        this.overlapTokens = Math.max(0, Math.min(overlapTokens, this.targetTokens / 2));
    }

    /**
     * Returns no chunks for short text; callers should keep using the document-level embedding.
     */
    public List<Chunk> chunk(String text) {
        String normalized = normalize(text);
        if (normalized == null) {
            return List.of();
        }

        List<Integer> tokenEnds = estimatedTokenEnds(normalized);
        if (tokenEnds.size() <= thresholdTokens) {
            return List.of();
        }

        int minimumTokens = Math.min(MIN_TARGET_TOKENS, targetTokens);
        List<Chunk> chunks = new ArrayList<>();
        int startToken = 0;
        while (startToken < tokenEnds.size()) {
            int remaining = tokenEnds.size() - startToken;
            int endToken;
            if (remaining <= targetTokens) {
                endToken = tokenEnds.size();
            } else {
                int minEnd = Math.min(tokenEnds.size(), startToken + minimumTokens);
                int targetEnd = Math.min(tokenEnds.size(), startToken + targetTokens);
                int maxEnd = Math.min(tokenEnds.size(), startToken + MAX_TARGET_TOKENS);
                endToken = chooseBoundary(normalized, tokenEnds, minEnd, targetEnd, maxEnd);
            }

            int startChar = startToken == 0 ? 0 : tokenEnds.get(startToken - 1);
            startChar = adjustStartToWordBoundary(normalized, startChar);
            int endChar = tokenEnds.get(endToken - 1);
            String chunkText = normalized.substring(startChar, endChar).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(new Chunk(chunks.size(), chunkText, endToken - startToken));
            }

            if (endToken >= tokenEnds.size()) {
                break;
            }
            int nextStart = endToken - overlapTokens;
            startToken = nextStart > startToken ? nextStart : endToken;
        }
        return List.copyOf(chunks);
    }

    public int estimateTokens(String text) {
        String normalized = normalize(text);
        return normalized == null ? 0 : estimatedTokenEnds(normalized).size();
    }

    private int chooseBoundary(String text, List<Integer> tokenEnds,
                               int minEnd, int targetEnd, int maxEnd) {
        int bestEnd = targetEnd;
        int bestScore = Integer.MAX_VALUE;
        for (int candidate = minEnd; candidate <= maxEnd; candidate++) {
            int charEnd = tokenEnds.get(candidate - 1);
            int priority = boundaryPriority(text, charEnd);
            int score = Math.abs(candidate - targetEnd) - priority * 80;
            if (score < bestScore || (score == bestScore && candidate > bestEnd)) {
                bestScore = score;
                bestEnd = candidate;
            }
        }
        return bestEnd;
    }

    private int boundaryPriority(String text, int charEnd) {
        if (charEnd <= 0 || charEnd > text.length()) {
            return 0;
        }
        int newlines = 0;
        for (int index = charEnd; index < text.length() && Character.isWhitespace(text.charAt(index)); index++) {
            if (text.charAt(index) == '\n') {
                newlines++;
            }
        }
        if (newlines >= 2) {
            return 4;
        }
        if (newlines == 1) {
            return 3;
        }
        char previous = text.charAt(charEnd - 1);
        if (isSentenceTerminator(previous)) {
            return 2;
        }
        if (Character.isWhitespace(previous)) {
            return 1;
        }
        return 0;
    }

    private int adjustStartToWordBoundary(String text, int startChar) {
        if (startChar <= 0 || startChar >= text.length()
                || Character.isWhitespace(text.charAt(startChar))) {
            return startChar;
        }
        int lowerBound = Math.max(0, startChar - 32);
        for (int index = startChar; index > lowerBound; index--) {
            char previous = text.charAt(index - 1);
            if (Character.isWhitespace(previous) || isSentenceTerminator(previous)) {
                return index;
            }
        }
        return startChar;
    }

    private List<Integer> estimatedTokenEnds(String text) {
        List<Integer> ends = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                index += charCount;
                continue;
            }
            if (isCjk(codePoint) || !Character.isLetterOrDigit(codePoint)) {
                index += charCount;
                ends.add(index);
                continue;
            }

            int runStart = index;
            int runCodePoints = 0;
            while (index < text.length()) {
                int current = text.codePointAt(index);
                if (!Character.isLetterOrDigit(current) || isCjk(current)) {
                    break;
                }
                index += Character.charCount(current);
                runCodePoints++;
                if (runCodePoints % 4 == 0) {
                    ends.add(index);
                }
            }
            if (runCodePoints % 4 != 0 || index == runStart) {
                ends.add(index);
            }
        }
        return ends;
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private boolean isSentenceTerminator(char value) {
        return value == '.' || value == '!' || value == '?'
                || value == '。' || value == '！' || value == '？'
                || value == ';' || value == '；';
    }

    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public record Chunk(int index, String text, int estimatedTokens) {
    }
}
