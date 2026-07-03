package com.idata.profile.agentproxy.dto.t6;

import lombok.Data;

import java.util.List;

@Data
public class T6IdentifyRequest {

    /** 叙事 ID（必填）*/
    private String narrativeId;

    /** 叙事关联的 person 实体列表（可选，从 PG persons 表查）*/
    private List<PersonRef> persons;

    /** 叙事关联的 organization 实体列表（可选，从 PG organizations 表查）*/
    private List<OrganizationRef> organizations;

    /** 待识别的社交账号列表（必填，从 PG social_accounts 表查完整数据）*/
    private List<SocialAccountRef> socialAccounts;

    /** 账号发布的帖子列表（必填，从 PG media_contents 表查完整数据）*/
    private List<MediaContentRef> mediaContents;

    @Data
    public static class SocialAccountRef {
        private String accountId;
        private String handle;
        private String displayName;
        private String sourcePlatformId;
        private AccountMetrics accountMetrics;
        private VerificationStatus verificationStatus;
        private String registerTime;
    }

    @Data
    public static class AccountMetrics {
        private Long followers;
        private Long following;
        private Long posts;
        private String snapshotTime;
    }

    @Data
    public static class VerificationStatus {
        private Boolean verified;
        private String verifiedType;
    }

    @Data
    public static class MediaContentRef {
        private String contentId;
        private String sourceAccountId;
        private String bodyText;
        private String publishedAt;
        private String messageType;
        private List<String> topics;
        private List<String> mentionedEntityIds;
        private String rootContentId;
        private String parentContentId;
    }

    @Data
    public static class PersonRef {
        private String personId;
        private String canonicalName;
        private List<String> linkedAccountIds;
    }

    @Data
    public static class OrganizationRef {
        private String organizationId;
        private String canonicalName;
        private List<String> verifiedAccountIds;
    }
}
