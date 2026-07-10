package com.idata.profile.agentproxy.dto.t2;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class T2ExtractResponse {
    private String contentId;
    private List<ExtractedMention> entities;
    private List<ExtractedRelationMention> relations;
    private String resolvedAuthorAccountId;
    private String modelVersion;
    private String raw;

    /**
     * @deprecated Use {@link #relations}. Kept only for older internal agent prompt code.
     */
    @Deprecated
    public List<ExtractedRelationMention> getRelationships() {
        return relations;
    }

    /**
     * @deprecated Use {@link #relations}. Kept only for older internal agent prompt code.
     */
    @Deprecated
    public void setRelationships(List<ExtractedRelationMention> relationships) {
        this.relations = relationships;
    }

    /**
     * @deprecated Events are represented as entity mentions with type=event.
     */
    @Deprecated
    public List<Object> getEvents() {
        return List.of();
    }

    /**
     * @deprecated Events are represented as entity mentions with type=event.
     */
    @Deprecated
    public void setEvents(List<Object> events) {
        // no-op: v1.1 stores events in entities.
    }

    @Data
    public static class ExtractedMention {
        private String mentionId;
        private String name;
        private String canonicalName;
        private String type;
        private Span span;
        private List<String> aliases;
        private Double importanceScore;
        private Double confidence;
        private Map<String, Object> attributes;

        public void assignSpan(Span span) {
            this.span = span;
        }

        public Span spanValue() {
            return span;
        }

        @Data
        public static class Span {
            private Integer start;
            private Integer end;
        }
    }

    @Data
    public static class ExtractedRelationMention {
        private String relationMentionId;
        private String subjectMentionId;
        private String predicate;
        private String objectMentionId;
        private Double confidence;
        private String evidence;

        /**
         * @deprecated Use {@link #predicate}. Kept only for older internal agent prompt code.
         */
        @Deprecated
        public String getRelationType() {
            return predicate;
        }

        /**
         * @deprecated Use {@link #predicate}. Kept only for older internal agent prompt code.
         */
        @Deprecated
        public void setRelationType(String relationType) {
            this.predicate = relationType;
        }
    }
}
