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
    /**
     * key: contentId，value: ES 相关性得分。只有走过 ES 关键词检索这条通道的内容才有值，
     * 纯语义/Neo4j/图片检索命中的内容这里没有对应的key，不代表分数是0
     */
    private Map<String, Double> scores;
}
