package com.idata.profile.agentproxy.dto.t3;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class T3ResolveBatchRequest {
    private List<ResolveItem> items;
    private Strategy strategy;

    @Data
    public static class ResolveItem {
        private Mention mention;
        private List<Candidate> candidates;
        private Context context;
    }

    @Data
    public static class Mention {
        private String mentionId;
        private String name;
        private String normalizedName;
        private String type;
        private List<String> aliases;
        private Map<String, Object> attributes;
    }

    @Data
    public static class Candidate {
        private String entityId;
        private String canonicalName;
        private String type;
        private List<String> aliases;
        private Double importanceScore;
        private Map<String, Object> attributes;
        private Double score;
        private String[] retrievalChannels;
    }

    @Data
    public static class Context {
        private String docId;
        private String textWindow;
        private String language;
    }

    @Data
    public static class Strategy {
        private Double autoMergeThreshold;
        private Double reviewThreshold;
    }
}
