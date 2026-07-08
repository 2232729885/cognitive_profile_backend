package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateAccountRequest {
    private String platform;
    private String platformUserId;
    /** user|channel|page|group|community|forum_board|news_source */
    private String accountEntityType;
    private String platformNativeType;

    private String handle;
    private String displayName;
    private String bio;
    private String selfDeclaredLocation;
    private Boolean verified;
    /** none|blue|org|government|media */
    private String verifiedType;
    private Boolean isSuspended;
    private String accountCreatedAt;

    private Long followersCount;
    private Long followingCount;
    private Long subscriberCount;
    private Long memberCount;
    private Long postCount;
    private Long viewCount;

    /** Optional recent text samples from this account. Pass an empty list when unavailable. */
    private List<String> recentPostSamples;
}
