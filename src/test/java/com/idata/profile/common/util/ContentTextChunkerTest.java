package com.idata.profile.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTextChunkerTest {

    private final ContentTextChunker chunker = new ContentTextChunker(800, 600, 75);

    @Test
    void shortTextKeepsDocumentOnly() {
        assertTrue(chunker.chunk("这是一段短正文。").isEmpty());
    }

    @Test
    void longTextIsSplitIntoBoundedOverlappingChunks() {
        String text = IntStream.range(0, 140)
                .mapToObj(index -> "第" + index + "段包含若干用于测试语义切分的中文字符，并且拥有完整句子。")
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();

        List<ContentTextChunker.Chunk> chunks = chunker.chunk(text);

        assertTrue(chunks.size() >= 2);
        assertEquals(0, chunks.get(0).index());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.estimatedTokens() <= 800));
        assertTrue(chunks.stream().allMatch(chunk -> !chunk.text().isBlank()));
        assertTrue(hasSharedText(chunks.get(0).text(), chunks.get(1).text()));
    }

    @Test
    void normalizesLineEndingsAndPreservesParagraphContent() {
        String paragraph = "A paragraph with enough repeated words to exercise splitting. ";
        String text = paragraph.repeat(80) + "\r\n\r\n" + paragraph.repeat(80);

        List<ContentTextChunker.Chunk> chunks = chunker.chunk(text);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.text().contains("\r")));
    }

    private boolean hasSharedText(String left, String right) {
        int sampleLength = Math.min(40, right.length());
        return sampleLength > 0 && left.contains(right.substring(0, sampleLength));
    }
}
