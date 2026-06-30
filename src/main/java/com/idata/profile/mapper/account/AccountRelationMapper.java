package com.idata.profile.mapper.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.account.AccountRelation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AccountRelationMapper extends BaseMapper<AccountRelation> {

    @Select("SELECT EXISTS(SELECT 1 FROM account_relations " +
            "WHERE source_platform_user_id = #{sourcePlatformUserId} " +
            "AND target_platform_user_id = #{targetPlatformUserId} " +
            "AND relation_type = #{relationType} " +
            "AND observed_at = #{observedAt})")
    boolean existsByNaturalKey(@Param("sourcePlatformUserId") String sourcePlatformUserId,
                               @Param("targetPlatformUserId") String targetPlatformUserId,
                               @Param("relationType") String relationType,
                               @Param("observedAt") OffsetDateTime observedAt);

    @Update("UPDATE account_relations ar SET from_account_id = sa.id " +
            "FROM social_accounts sa WHERE ar.from_account_id IS NULL " +
            "AND sa.platform = ar.platform AND sa.platform_user_id = ar.source_platform_user_id")
    int backfillFromAccountId();

    @Update("UPDATE account_relations ar SET to_account_id = sa.id " +
            "FROM social_accounts sa WHERE ar.to_account_id IS NULL " +
            "AND sa.platform = ar.platform AND sa.platform_user_id = ar.target_platform_user_id")
    int backfillToAccountId();

    @Select("SELECT * FROM account_relations WHERE from_account_id IS NOT NULL " +
            "AND to_account_id IS NOT NULL AND synced_to_neo4j = FALSE LIMIT #{limit}")
    List<AccountRelation> selectReadyForNeo4jSync(@Param("limit") int limit);

    @Update("UPDATE account_relations SET synced_to_neo4j = TRUE WHERE id = #{id}")
    int markSyncedToNeo4j(@Param("id") java.util.UUID id);

    @Select("SELECT relation_type AS relationType, COUNT(*) AS orphanCount " +
            "FROM account_relations WHERE (from_account_id IS NULL OR to_account_id IS NULL) " +
            "AND created_at < NOW() - (#{days} || ' days')::INTERVAL GROUP BY relation_type")
    List<java.util.Map<String, Object>> countOrphansOlderThan(@Param("days") int days);
}
