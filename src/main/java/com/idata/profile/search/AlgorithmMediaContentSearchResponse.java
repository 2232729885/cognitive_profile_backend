package com.idata.profile.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlgorithmMediaContentSearchResponse {
    private String code;
    private String msg;
    private DataPayload data;

    public static AlgorithmMediaContentSearchResponse ok(String query, long durationMs, List<Hit> hits) {
        List<Hit> safeHits = hits == null ? List.of() : hits;
        return new AlgorithmMediaContentSearchResponse(
                "200",
                "success",
                new DataPayload(query, safeHits.size(), durationMs, safeHits));
    }

    public static AlgorithmMediaContentSearchResponse fail(String code, String msg) {
        return new AlgorithmMediaContentSearchResponse(
                code,
                msg,
                new DataPayload(null, 0, 0L, List.of()));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DataPayload {
        private String query;
        private int total;
        private long durationMs;
        private List<Hit> hits;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Hit {
        private String contentId;
        private String platform;
        private String contentType;
        private String platformContentId;
        private String authorPlatformUserId;
        private String title;
        private String bodyText;
        private String language;
        private OffsetDateTime publishedAt;
        private String url;
        private String[] hashtags;
        private String[] mentions;
        private Long likeCount;
        private Long commentCount;
        private Long shareCount;
        private Long repostCount;
        private Long quoteCount;
        private Long viewCount;
        private Long reactionCount;
        private Double rankScore;
        private Double rrfScore;
        private String matchLevel;
        private String dominantHitType;
        private String displaySuggestion;
        private Double keywordScore;
        private Double semanticScore;
        private Double fusionScore;
        private Map<String, List<String>> highlights;
        private List<SearchResult.AssetHit> matchedAssets;
        private SearchResult.AssetHit primaryAsset;
        private List<SearchResult.Evidence> evidences;
    }
}
