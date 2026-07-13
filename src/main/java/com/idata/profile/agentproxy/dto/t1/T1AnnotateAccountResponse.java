package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateAccountResponse {

    /** 固定为 "t1_annotation_v0.6" */
    private String schemaVersion;

    /** 回显请求里的关键身份字段，不复制完整账户资料 */
    private AccountReference accountReference;

    private AccountType accountType;

    private List<EvidenceClue> evidenceClues;

    /** 复用 T1AnnotateResponse 的结构（needHumanReview/reviewReasons/failedModules三个字段一样），
     *  但这里的 reviewReasons/failedModules 枚举值是账号场景专用的，跟内容标注不通用，见 README */
    private T1AnnotateResponse.QualityControl qualityControl;

    private Double overallConfidence;

    private String processedAt;

    @Data
    public static class AccountReference {
        private String platform;
        private String platformUserId;
        /** user | channel | page | group | community | forum_board | news_source */
        private String accountEntityType;
        private String platformNativeType;
        private String handle;
        private String displayName;
    }

    @Data
    public static class AccountType {
        private PrimaryAccountCategory primaryAccountCategory;
        private List<AccountSubtypeTag> accountSubtypeTags;
        private AutomationSuspicion automationSuspicion;

        @Data
        public static class PrimaryAccountCategory {
            /** ordinary_user/news_media/state_affiliated_media/government_agency/political_actor/
             *  political_party_or_campaign/military_security_agency/international_organization/
             *  ngo_or_civil_society/academic_or_expert/commercial_brand/platform_official/
             *  influencer_kol/community_group/anonymous_account/suspected_bot_or_automated/
             *  unknown/other */
            private String categoryLabel;
            private List<String> evidenceIds;
        }

        @Data
        public static class AccountSubtypeTag {
            /** 受控字符串，不是固定枚举 */
            private String subtypeTag;
            private List<String> evidenceIds;
        }

        @Data
        public static class AutomationSuspicion {
            /** none | low | medium | high | unclear */
            private String suspicionLevel;
            private List<String> evidenceIds;
        }
    }

    @Data
    public static class EvidenceClue {
        private String evidenceId;
        /** profile_text/verification_info/account_metadata/activity_statistics/
         *  recent_post_sample/platform_label/other */
        private String evidenceType;
        /** display_name/bio/self_declared_location/verified/verified_type/account_entity_type/
         *  platform_native_type/account_created_at/followers_count/following_count/
         *  subscriber_count/member_count/post_count/view_count/recent_post_sample/other */
        private String sourceField;
        /** 支撑判断的最小原始值快照，比如 {"value": "..."} 或者
         *  {"rawValues": {...}, "derivedValues": {...}}，没有固定结构 */
        private Object metadataSnapshot;
    }
}
