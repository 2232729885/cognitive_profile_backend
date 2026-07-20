package com.idata.profile.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.idata.profile.entity.content.MediaContent;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
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
    /**
     * key: contentId，value: Milvus 向量相似度（余弦相似度）。只有走过语义检索这条通道的内容才有值
     */
    private Map<String, Double> similarityScores;
    /**
     * key: contentId，value: RRF融合后的综合分数，决定了"智能融合"模式最终的排序。
     * 只有hybrid检索类型才会有这个字段，跟 scores/similarityScores 不是同一个量纲，
     * 单纯是多路排名融合出来的相对大小，本身没有绝对意义，只用来说明"融合排序是按这个数排的"
     */
    private Map<String, Double> fusionScores;

    /**
     * 内容融合检索结果。召回单位最终按 MediaContent 聚合；每条结果保留文本/媒体两类 RRF 贡献、
     * 展示建议和命中证据，方便前端决定优先展示贴文正文还是命中的媒体资源。
     */
    private List<ContentHit> contentHits;

    @Data
    public static class ContentHit {
        private String contentId;
        private Double rrfScore;
        private Double rankScore;
        private String matchLevel;
        private Integer matchLevelRank;
        private String dominantHitType;
        private String displaySuggestion;
        private Contribution contribution;
        private AssetHit primaryAsset;
        private List<AssetHit> matchedAssets;
        private List<Evidence> evidences;
        private MediaContent post;
    }

    @Data
    public static class Contribution {
        private ContributionSide text;
        private ContributionSide media;
    }

    @Data
    public static class ContributionSide {
        private Double rrfScore;
        private Double ratio;
    }

    @Data
    public static class AssetHit {
        private String entityId;
        private String assetId;
        private String contentId;
        private String mediaType;
        private String previewUrl;
        private String sourceUrl;
        private String storageUri;
        private String minioBucket;
        private String minioKey;
        private String mimeType;
        private Integer width;
        private Integer height;
        private Long segmentStartMs;
        private Long segmentEndMs;
        private Long previewTimeMs;
        private Double rrfContribution;
    }

    @Data
    public static class Evidence {
        private String channel;
        private String category;
        private Integer rank;
        private Double rrfContribution;
        private Double rawScore;
        private String hitField;
        private String contentId;
        private String assetId;
        private String entityId;
    }
}
