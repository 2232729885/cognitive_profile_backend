package com.idata.profile.mapper.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.account.SocialAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface SocialAccountMapper extends BaseMapper<SocialAccount> {

    @Select("SELECT * FROM social_accounts WHERE platform = #{platform} AND platform_user_id = #{platformUserId}")
    SocialAccount selectByPlatformAndUserId(@Param("platform") String platform,
                                            @Param("platformUserId") String platformUserId);

    @Select("SELECT * FROM social_accounts WHERE entity_person_id = #{personId}")
    List<SocialAccount> selectByEntityPersonId(@Param("personId") UUID personId);

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
                account_entity_type = COALESCE(EXCLUDED.account_entity_type, social_accounts.account_entity_type),
                platform_native_type = COALESCE(EXCLUDED.platform_native_type, social_accounts.platform_native_type),
                handle = COALESCE(EXCLUDED.handle, social_accounts.handle),
                display_name = COALESCE(EXCLUDED.display_name, social_accounts.display_name),
                bio = COALESCE(EXCLUDED.bio, social_accounts.bio),
                avatar_url = COALESCE(EXCLUDED.avatar_url, social_accounts.avatar_url),
                profile_url = COALESCE(EXCLUDED.profile_url, social_accounts.profile_url),
                self_declared_location = COALESCE(EXCLUDED.self_declared_location, social_accounts.self_declared_location),
                verified = COALESCE(#{account.verified}, social_accounts.verified),
                verified_type = COALESCE(EXCLUDED.verified_type, social_accounts.verified_type),
                is_suspended = COALESCE(#{account.isSuspended}, social_accounts.is_suspended),
                account_created_at = COALESCE(EXCLUDED.account_created_at, social_accounts.account_created_at),
                followers_count = COALESCE(#{account.followersCount}, social_accounts.followers_count),
                following_count = COALESCE(#{account.followingCount}, social_accounts.following_count),
                subscriber_count = COALESCE(#{account.subscriberCount}, social_accounts.subscriber_count),
                member_count = COALESCE(#{account.memberCount}, social_accounts.member_count),
                post_count = COALESCE(#{account.postCount}, social_accounts.post_count),
                view_count = COALESCE(#{account.viewCount}, social_accounts.view_count),
                account_type = COALESCE(EXCLUDED.account_type, social_accounts.account_type),
                account_type_confidence = COALESCE(EXCLUDED.account_type_confidence, social_accounts.account_type_confidence),
                entity_person_id = COALESCE(EXCLUDED.entity_person_id, social_accounts.entity_person_id),
                entity_org_id = COALESCE(EXCLUDED.entity_org_id, social_accounts.entity_org_id),
                latest_snapshot_at = COALESCE(EXCLUDED.latest_snapshot_at, social_accounts.latest_snapshot_at),
                last_active_at = COALESCE(EXCLUDED.last_active_at, social_accounts.last_active_at),
                updated_at = NOW()
            RETURNING id
            """, affectData = true)
    UUID upsertByPlatformAndUserId(@Param("account") SocialAccount account);

    @Update("UPDATE social_accounts SET entity_person_id = #{entityPersonId}, updated_at = NOW() WHERE id = #{accountId}")
    int updateEntityPersonId(@Param("accountId") UUID accountId,
                             @Param("entityPersonId") UUID entityPersonId);

    @Update("UPDATE social_accounts SET entity_org_id = #{entityOrgId}, updated_at = NOW() WHERE id = #{accountId}")
    int updateEntityOrgId(@Param("accountId") UUID accountId,
                          @Param("entityOrgId") UUID entityOrgId);

    @Update("UPDATE social_accounts SET identity_resolved_at = NOW() WHERE id = #{accountId}")
    int markIdentityResolved(@Param("accountId") UUID accountId);

    @Select("SELECT * FROM social_accounts WHERE identity_resolved_at IS NULL " +
            "AND account_type IS NOT NULL ORDER BY created_at ASC LIMIT #{limit}")
    List<SocialAccount> selectPendingIdentityResolution(@Param("limit") int limit);
}
