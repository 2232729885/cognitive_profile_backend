package com.idata.profile.mapper.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.account.SocialAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface SocialAccountMapper extends BaseMapper<SocialAccount> {

    @Select("SELECT * FROM social_accounts WHERE platform = #{platform} AND platform_user_id = #{platformUserId}")
    SocialAccount selectByPlatformAndUserId(@Param("platform") String platform,
                                            @Param("platformUserId") String platformUserId);

    @Select(value = """
            INSERT INTO social_accounts (
                id, platform, platform_user_id, account_entity_type, platform_native_type,
                handle, display_name, bio, avatar_url, profile_url, self_declared_location,
                verified, verified_type, is_suspended, account_created_at,
                followers_count, following_count, subscriber_count, member_count, post_count, view_count,
                account_type, account_type_confidence, entity_person_id, entity_org_id,
                latest_snapshot_at, first_seen_at, last_active_at
            )
            VALUES (
                #{account.id}, #{account.platform}, #{account.platformUserId}, #{account.accountEntityType}, #{account.platformNativeType},
                #{account.handle}, #{account.displayName}, #{account.bio}, #{account.avatarUrl}, #{account.profileUrl}, #{account.selfDeclaredLocation},
                COALESCE(#{account.verified}, FALSE), #{account.verifiedType}, COALESCE(#{account.isSuspended}, FALSE), #{account.accountCreatedAt},
                COALESCE(#{account.followersCount}, 0), COALESCE(#{account.followingCount}, 0), COALESCE(#{account.subscriberCount}, 0),
                COALESCE(#{account.memberCount}, 0), COALESCE(#{account.postCount}, 0), COALESCE(#{account.viewCount}, 0),
                #{account.accountType}, #{account.accountTypeConfidence}, #{account.entityPersonId}, #{account.entityOrgId},
                #{account.latestSnapshotAt}, COALESCE(#{account.firstSeenAt}, NOW()), #{account.lastActiveAt}
            )
            ON CONFLICT (platform, platform_user_id) DO UPDATE SET
                account_entity_type = EXCLUDED.account_entity_type,
                platform_native_type = EXCLUDED.platform_native_type,
                handle = EXCLUDED.handle,
                display_name = EXCLUDED.display_name,
                bio = EXCLUDED.bio,
                avatar_url = EXCLUDED.avatar_url,
                profile_url = EXCLUDED.profile_url,
                self_declared_location = EXCLUDED.self_declared_location,
                verified = EXCLUDED.verified,
                verified_type = EXCLUDED.verified_type,
                is_suspended = EXCLUDED.is_suspended,
                account_created_at = EXCLUDED.account_created_at,
                followers_count = EXCLUDED.followers_count,
                following_count = EXCLUDED.following_count,
                subscriber_count = EXCLUDED.subscriber_count,
                member_count = EXCLUDED.member_count,
                post_count = EXCLUDED.post_count,
                view_count = EXCLUDED.view_count,
                account_type = COALESCE(EXCLUDED.account_type, social_accounts.account_type),
                account_type_confidence = COALESCE(EXCLUDED.account_type_confidence, social_accounts.account_type_confidence),
                entity_person_id = COALESCE(EXCLUDED.entity_person_id, social_accounts.entity_person_id),
                entity_org_id = COALESCE(EXCLUDED.entity_org_id, social_accounts.entity_org_id),
                latest_snapshot_at = EXCLUDED.latest_snapshot_at,
                last_active_at = COALESCE(EXCLUDED.last_active_at, social_accounts.last_active_at),
                updated_at = NOW()
            RETURNING id
            """, affectData = true)
    UUID upsertByPlatformAndUserId(@Param("account") SocialAccount account);

    @Update("UPDATE social_accounts SET entity_person_id = #{entityPersonId}, updated_at = NOW() WHERE id = #{accountId}")
    int updateEntityPersonId(@Param("accountId") UUID accountId,
                             @Param("entityPersonId") UUID entityPersonId);
}
