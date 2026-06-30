package com.idata.profile.agentproxy.dto.t6;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** T6目标识别响应。BEND手法分类、协同群体检测、T00-T10目标类型识别结果。 */
@Data
public class T6IdentifyResponse {
    private List<IdentifiedTarget> targets;
    private Integer accountsAnalyzed;
    private Integer groupsDetected;

    @Data
    public static class IdentifiedTarget {
        private String targetType;          // T00~T10
        private String targetEntityType;    // social_account|person|organization
        private String targetEntityId;
        private BigDecimal confidence;
        private Object bendDistribution;    // {Distort, Dismiss, Amplify, Narrativize, other}
        private String evidenceText;        // LLM生成的中文证据说明
        private List<String> evidenceContentIds;
    }
}
