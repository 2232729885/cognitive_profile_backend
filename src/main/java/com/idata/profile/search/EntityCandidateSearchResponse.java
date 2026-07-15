package com.idata.profile.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
        DataPayload data = new DataPayload(
                traceId,
                keywordCandidates == null ? List.of() : keywordCandidates,
                semanticCandidates == null ? List.of() : semanticCandidates);
        return new EntityCandidateSearchResponse("200", "success", data);
    }

    public static EntityCandidateSearchResponse fail(String code, String msg) {
        return new EntityCandidateSearchResponse(code, msg, new DataPayload(null, List.of(), List.of()));
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
        private Double score;
    }
}
