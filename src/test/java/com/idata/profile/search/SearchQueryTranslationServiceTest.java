package com.idata.profile.search;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchQueryTranslationServiceTest {

    @Test
    void splitsLongMediaTextAtBoundariesAndHonorsChunkLimit() {
        SearchQueryTranslationService service = new SearchQueryTranslationService(1);
        ReflectionTestUtils.setField(service, "mediaChunkChars", 300);
        ReflectionTestUtils.setField(service, "mediaMaxChunks", 3);
        String text = "第一段需要翻译的媒体文字。".repeat(120);

        List<String> chunks = service.splitMediaTranslationChunks(text);

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 300));
        assertTrue(chunks.stream().allMatch(chunk -> !chunk.isBlank()));
    }

    @Test
    void keepsShortMediaTextInOneChunk() {
        SearchQueryTranslationService service = new SearchQueryTranslationService(1);
        ReflectionTestUtils.setField(service, "mediaChunkChars", 1_500);
        ReflectionTestUtils.setField(service, "mediaMaxChunks", 4);

        assertEquals(List.of("MBC 뉴스데스크 김영록"),
                service.splitMediaTranslationChunks("MBC 뉴스데스크 김영록"));
    }
}
