package com.idata.profile.agentproxy.dto.t3;

import lombok.Data;

import java.util.List;

/** T3信息融合请求。输入T2抽取的实体列表，做跨源归一和关系融合。 */
@Data
public class T3FuseRequest {
    private List<T2EntityRef> entities;

    @Data
    public static class T2EntityRef {
        private String type;
        private String canonicalName;
        private String tempId;   // T2阶段的临时实体引用
    }
}
