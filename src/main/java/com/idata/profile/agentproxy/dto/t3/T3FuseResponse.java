package com.idata.profile.agentproxy.dto.t3;

import lombok.Data;

import java.util.List;

/**
 * T3信息融合响应。
 *
 * entityMerges: 实体归一结果，合并多个实体为一个时记录mergeHistory（正常归一逻辑，要保留追溯链）。
 * nodes/relations: 写入Neo4j的节点和关系，字段冲突时新值直接覆盖旧值（SET），不暂存不审核——
 *   这一点和上面的entityMerges是两件不同的事，不要混淆，见
 *   docs/课题四_数据处理流程_v2.md 1.3节核心设计原则第5条。
 */
@Data
public class T3FuseResponse {
    private List<EntityMerge> entityMerges;
    private List<Neo4jNode> nodes;
    private List<Neo4jRelation> relations;
    private String raw;   // 完整原始响应，存入raw_records.t3_output

    @Data
    public static class EntityMerge {
        private String survivorId;
        private List<String> mergedIds;
    }

    @Data
    public static class Neo4jNode {
        private String label;     // Person|Organization|Event|Narrative|SocialAccount
        private String id;
        private Object properties; // 完整属性，SET时新值覆盖旧值
    }

    @Data
    public static class Neo4jRelation {
        private String fromId;
        private String toId;
        private String relationType;
        private Object properties;
    }
}
