package com.idata.profile.ingestion.normalizer;

import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.account.SocialAccountSnapshot;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.ingestion.consumer.IngestionMessageSupport;
import lombok.Data;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class SocialAccountNormalizer {

    public NormalizedAccount normalize(Object kafkaMessage, RawRecord rawRecord) {
        JsonNode root = IngestionMessageSupport.root(kafkaMessage);
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);
        JsonNode metrics = data.path("metrics");

        SocialAccount account = new SocialAccount();
        account.setId(UUID.randomUUID());
        account.setPlatform(IngestionMessageSupport.text(root, "platform"));
        account.setPlatformUserId(IngestionMessageSupport.text(data, "platform_user_id"));
        account.setAccountEntityType(IngestionMessageSupport.text(data, "account_entity_type"));
        account.setPlatformNativeType(IngestionMessageSupport.text(data, "platform_native_type"));
        account.setHandle(IngestionMessageSupport.text(data, "handle"));
        account.setDisplayName(IngestionMessageSupport.text(data, "display_name"));
        account.setBio(IngestionMessageSupport.text(data, "bio"));
        account.setAvatarUrl(IngestionMessageSupport.text(data, "avatar_url"));
        account.setProfileUrl(IngestionMessageSupport.text(data, "profile_url"));
        account.setSelfDeclaredLocation(IngestionMessageSupport.text(data, "self_declared_location"));
        account.setVerified(readBoolean(data, "verified"));
        account.setVerifiedType(IngestionMessageSupport.text(data, "verified_type"));
        account.setIsSuspended(readBoolean(data, "is_suspended"));
        account.setAccountCreatedAt(IngestionMessageSupport.parseOffsetDateTime(data.path("account_created_at")));
        account.setFollowersCount(readLong(metrics, "followers_count"));
        account.setFollowingCount(readLong(metrics, "following_count"));
        account.setSubscriberCount(readLong(metrics, "subscriber_count"));
        account.setMemberCount(readLong(metrics, "member_count"));
        account.setPostCount(readLong(metrics, "post_count"));
        account.setViewCount(readLong(metrics, "view_count"));
        account.setLatestSnapshotAt(rawRecord.getCollectedAt());
        account.setFirstSeenAt(OffsetDateTime.now());

        SocialAccountSnapshot snapshot = new SocialAccountSnapshot();
        snapshot.setId(UUID.randomUUID());
        snapshot.setAccountId(account.getId());
        snapshot.setRawRecordId(rawRecord.getId());
        snapshot.setSnapshotAt(rawRecord.getCollectedAt());
        snapshot.setDisplayName(account.getDisplayName());
        snapshot.setBio(account.getBio());
        snapshot.setVerified(account.getVerified());
        snapshot.setVerifiedType(account.getVerifiedType());
        snapshot.setSelfDeclaredLocation(account.getSelfDeclaredLocation());
        snapshot.setIsSuspended(account.getIsSuspended());
        snapshot.setFollowersCount(account.getFollowersCount());
        snapshot.setFollowingCount(account.getFollowingCount());
        snapshot.setSubscriberCount(account.getSubscriberCount());
        snapshot.setMemberCount(account.getMemberCount());
        snapshot.setPostCount(account.getPostCount());
        snapshot.setViewCount(account.getViewCount());
        snapshot.setSnapshotPayload(null);

        return new NormalizedAccount(account, snapshot);
    }

    private Boolean readBoolean(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText();
        if (!IngestionMessageSupport.hasText(text)) {
            return null;
        }
        return Boolean.parseBoolean(text.trim());
    }

    private Long readLong(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        String text = value.asText();
        if (!IngestionMessageSupport.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Data
    public static class NormalizedAccount {
        private final SocialAccount account;
        private final SocialAccountSnapshot snapshot;
    }
}
