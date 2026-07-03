package com.idata.profile.agentproxy.dto.t3;

import lombok.Data;

import java.util.List;

/**
 * T3信息融合请求。输入T2抽取的实体、文本层关系和事件，由T3做跨源归一和关系融合。
 */
@Data
public class T3FuseRequest {
    private List<T2EntityRef> entities;
    private List<T2RelationRef> relationships;
    private List<T2EventRef> events;

    @Data
    public static class T2EntityRef {
        private String type;
        private String canonicalName;
        private String tempId;
        private List<String> aliases;
    }

    @Data
    public static class T2RelationRef {
        private String sourceName;
        private String sourceType;
        private String targetName;
        private String targetType;
        private String relationType;
        private String role;
        private Double confidence;
    }

    @Data
    public static class T2EventRef {
        private String eventType;
        private String canonicalName;
        private String eventTimeStart;
        private Double confidence;
        private List<T2EntityRef> participants;
    }
}
