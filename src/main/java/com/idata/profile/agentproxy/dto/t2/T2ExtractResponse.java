package com.idata.profile.agentproxy.dto.t2;

import lombok.Data;

import java.util.List;

/**
 * T2信息抽取响应。识别出的实体（人物/组织/事件/叙事）及关系。
 * 实体写入PG精简表（persons/organizations/events/narratives），
 * 详细属性和关系留给T3写Neo4j。
 */
@Data
public class T2ExtractResponse {
    private List<ExtractedEntity> entities;
    private String resolvedAuthorAccountId;  // 若T2能识别出作者对应的account UUID
    private String raw;                       // 完整原始响应，存入raw_records.t2_output

    @Data
    public static class ExtractedEntity {
        private String type;        // person|organization|event|narrative
        private String canonicalName;
        private java.math.BigDecimal importanceScore;
        private String matchedAccountId;  // type=person时，可能关联的account UUID
    }
}
