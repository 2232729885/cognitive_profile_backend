package com.idata.profile.agentproxy.dto.t3;

import lombok.Data;

import java.util.List;

@Data
public class T3ResolveResponse {
    private List<MergeGroup> mergeGroups;
    private List<DisjointPair> disjointPairs;
    private List<String> uncertain;

    @Data
    public static class MergeGroup {
        private String survivorId;
        private List<String> mergedIds;
        private Double confidence;
        private String matchMethod;
    }

    @Data
    public static class DisjointPair {
        private String id1;
        private String id2;
        private Double confidence;
    }
}
