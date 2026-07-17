package com.idata.profile.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityCandidateSearchResponse {

    private String code;
    private String msg;
    private DataPayload data;

    public static EntityCandidateSearchResponse ok(String traceId,
                                                   List<Candidate> keywordCandidates,
                                                   List<Candidate> semanticCandidates) {
        return ok(traceId, keywordCandidates, semanticCandidates, List.of());
    }

    public static EntityCandidateSearchResponse ok(String traceId,
                                                   List<Candidate> keywordCandidates,
                                                   List<Candidate> semanticCandidates,
                                                   List<Candidate> hybridCandidates) {
        DataPayload data = new DataPayload(
                traceId,
                keywordCandidates == null ? List.of() : keywordCandidates,
                semanticCandidates == null ? List.of() : semanticCandidates,
                hybridCandidates == null ? List.of() : hybridCandidates);
        return new EntityCandidateSearchResponse("200", "success", data);
    }

    public static EntityCandidateSearchResponse fail(String code, String msg) {
        return new EntityCandidateSearchResponse(code, msg, new DataPayload(null, List.of(), List.of(), List.of()));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataPayload {
        @JsonProperty("trace_id")
        private String traceId;
        @JsonProperty("keyword_candidates")
        private List<Candidate> keywordCandidates;
        @JsonProperty("semantic_candidates")
        private List<Candidate> semanticCandidates;
        @JsonProperty("hybrid_candidates")
        private List<Candidate> hybridCandidates;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {
        @JsonProperty("entity_id")
        private String entityId;
        private String name;
        @JsonProperty("entity_type")
        private String entityType;
        /**
         * Backward-compatible primary score. For hybrid retrieval this is the backend final rank score.
         */
        private Double score;
        @JsonProperty("keyword_score")
        private Double keywordScore;
        @JsonProperty("keyword_field")
        private String keywordField;
        @JsonProperty("semantic_score")
        private Double semanticScore;
        @JsonProperty("semantic_field")
        private String semanticField;
        @JsonProperty("fusion_score")
        private Double fusionScore;
        @JsonProperty("matched_channels")
        private List<String> matchedChannels;
        private Map<String, Object> metadata;

        public Candidate(String entityId, String name, String entityType, Double score) {
            this.entityId = entityId;
            this.name = name;
            this.entityType = entityType;
            this.score = score;
        }
    }
}
