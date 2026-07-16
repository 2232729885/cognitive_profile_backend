package com.idata.profile.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class EntityCandidateSearchRequest {

    private String query;

    @JsonProperty("entity_types")
    private List<String> entityTypes;

    @JsonProperty("top_k")
    private int topK = 10;

    /**
     * keyword: 只返回关键词召回候选；semantic: 只返回语义向量召回候选；both: 两路都返回。
     */
    @JsonProperty("retrieval_mode")
    private String retrievalMode = "both";

    /**
     * Optional threshold for semantic candidates in retrieval_mode=hybrid.
     * Null means no semantic score filtering.
     */
    @JsonProperty("semantic_min_score")
    private Double semanticMinScore;

    @JsonProperty("trace_id")
    private String traceId;

    private Map<String, Object> context;
}
