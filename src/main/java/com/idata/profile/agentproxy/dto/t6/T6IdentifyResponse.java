package com.idata.profile.agentproxy.dto.t6;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class T6IdentifyResponse {

    /** 账号级识别结果（每个账号一条）*/
    private List<AccountIdentifyResult> accountIdentifyResult;

    /** 实体级聚合结果（person/organization）*/
    private List<EntityIdentifyResult> entityIdentifyResult;

    /** 群体级识别结果（协同行为账号聚类）*/
    private List<GroupIdentifyResult> groupIdentifyResult;

    /** 统计摘要 */
    private Summary summary;

    @Data
    public static class AccountIdentifyResult {
        private String accountId;
        private String targetType;
        private String targetTypeName;
        private BigDecimal confidence;
        private BigDecimal csiScore;
        private MatchEvidence evidence;
        private String filterReason;
    }

    @Data
    public static class MatchEvidence {
        private String matchedTacticId;
        private Double distributionSimilarity;
        private Double sequenceSimilarity;
        private Double combinedScore;
        private String runnerUpTacticId;
        private Double runnerUpScore;
        private String evidence;
    }

    @Data
    public static class EntityIdentifyResult {
        private String entityId;
        private String entityType;
        private String targetType;
        private String targetTypeName;
        private BigDecimal confidence;
        private List<String> accountIds;
        private String dominantAccountId;
        private String evidence;
    }

    @Data
    public static class GroupIdentifyResult {
        private String groupId;
        private List<String> accountIds;
        private String dominantTactic;
        private String dominantTacticName;
        private String evidence;
    }

    @Data
    public static class Summary {
        private String narrativeId;
        private Integer totalAccounts;
        private Integer t00Count;
        private Integer suspectCount;
        private Integer identifiedCount;
        private Integer unknownCount;
        private Integer groupCount;
        private Long processingTimeMs;
    }
}
