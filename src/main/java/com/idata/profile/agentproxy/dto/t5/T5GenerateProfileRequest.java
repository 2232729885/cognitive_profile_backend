package com.idata.profile.agentproxy.dto.t5;

import lombok.Data;

@Data
public class T5GenerateProfileRequest {
    /**
     * 目标实体 ID（RZDK字段名为 target_id，类型为 String）。
     * 目前只支持 person 类型，传 persons.id 的 UUID 字符串。
     * T5 自行连接 PG 和 Neo4j 查询完整数据，不需要后端传入。
     */
    private String targetId;

    /**
     * 目标实体类型（RZDK字段，当前固定为 "person"）
     */
    private String targetType = "person";
}
