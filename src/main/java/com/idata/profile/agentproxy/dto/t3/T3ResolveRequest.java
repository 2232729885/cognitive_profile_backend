package com.idata.profile.agentproxy.dto.t3;

import lombok.Data;

import java.util.List;
import java.util.Map;

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
        /**
         * 平台原生标识符，供 T3 做高置信度标识符匹配使用。
         * key 是平台名（如 "x"/"twitter"/"telegram"/"youtube"），
         * value 是该平台的原生用户 ID（platform_user_id）。
         * 示例：{"x": "POTUS", "telegram": "12345678"}
         * 对于 person 类型实体，通过关联的 social_accounts.platform_user_id 填充；
         * 对于 organization/event/narrative，通常为空 Map。
         */
        private Map<String, String> sourceIdentifiers;
    }
}
