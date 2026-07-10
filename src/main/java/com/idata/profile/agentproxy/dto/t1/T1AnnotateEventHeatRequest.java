package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateEventHeatRequest {
    private EventInfo event;
    private List<RelatedEntity> relatedEntities;
    private AggregateStats aggregateStats;

    @Data
    public static class EventInfo {
        private String eventId;
        private String canonicalName;
        private String eventType;
        private String occurredAtStart;
        private String occurredAtEnd;
        private String country;
    }

    @Data
    public static class RelatedEntity {
        private String entityId;
        /** person | organization | location | media_content | social_account */
        private String entityType;
        private String name;
        /** Only populated when entityType=media_content. */
        private String publishedAt;
        private String platform;
        private Long likeCount;
        private Long commentCount;
        private Long shareCount;
        private Long repostCount;
        private Long viewCount;
    }

    @Data
    public static class AggregateStats {
        /** Actual graph-related content count before Top-N sampling. */
        private Integer totalRelatedContentCount;
        private Long totalEngagement;
        private Integer distinctPlatformCount;
        private String earliestContentAt;
        private String latestContentAt;
    }
}
