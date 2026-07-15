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

    @JsonProperty("trace_id")
    private String traceId;

    private Map<String, Object> context;
}
