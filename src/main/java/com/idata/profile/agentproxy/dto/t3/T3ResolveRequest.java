package com.idata.profile.agentproxy.dto.t3;

import lombok.Data;

import java.util.List;

@Data
public class T3ResolveRequest {
    private List<EntityCandidate> entities;

    @Data
    public static class EntityCandidate {
        private String id;
        private String entityType;
        private String canonicalName;
        private List<String> aliases;
        private Double importanceScore;
    }
}
