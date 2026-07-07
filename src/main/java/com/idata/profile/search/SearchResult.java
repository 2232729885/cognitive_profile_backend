package com.idata.profile.search;

import com.idata.profile.entity.content.MediaContent;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SearchResult {
    private List<MediaContent> items;
    private int total;
    private String searchType;
    private long durationMs;
    /**
     * key: contentId，value: 高亮片段（body_text/title 命中的 HTML 片段）
     */
    private Map<String, Map<String, List<String>>> highlights;
}
