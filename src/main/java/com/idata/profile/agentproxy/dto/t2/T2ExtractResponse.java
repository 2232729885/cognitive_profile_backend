package com.idata.profile.agentproxy.dto.t2;

import lombok.Data;

import java.util.List;

/**
 * T2信息抽取响应。
 * entities写入PG精简实体表；relationships/events透传给T3做实体归一和Neo4j写入。
 */
@Data
public class T2ExtractResponse {
    private List<ExtractedEntity> entities;
    private List<ExtractedRelation> relationships;
    private List<ExtractedEvent> events;
    private String resolvedAuthorAccountId;
    private String raw;

    @Data
    public static class ExtractedEntity {
        private String type;
        private String canonicalName;
        private java.math.BigDecimal importanceScore;
        private String matchedAccountId;
        private List<String> aliases;
    }

    /**
     * T2抽取的文本层关系，端点还是实体名称，必须交给T3归一后才能写Neo4j。
     */
    @Data
    public static class ExtractedRelation {
        private String sourceName;
        private String sourceType;
        private String targetName;
        private String targetType;
        private String relationType;
        private String role;
        private Double confidence;
    }

    @Data
    public static class ExtractedEvent {
        private String eventType;
        private String canonicalName;
        private String eventTimeStart;
        private Double confidence;
        private List<EventParticipant> participants;

        @Data
        public static class EventParticipant {
            private String name;
            private String role;
        }
    }
}
