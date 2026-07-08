package com.idata.profile.agentproxy.dto.t3;

import lombok.Data;

import java.util.List;

@Data
public class T3ResolveBatchResponse {
    private List<ResolveResult> results;
    private String modelVersion;

    @Data
    public static class ResolveResult {
        private String mentionId;
        private String action;
        private String matchedEntityId;
        private Double score;
        private Double confidence;
        private String matchMethod;
        private String reason;
        /** Internal propagation of the top-level modelVersion for downstream audit records. */
        private String modelVersion;
    }
}
