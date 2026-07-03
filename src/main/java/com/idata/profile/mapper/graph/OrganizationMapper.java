package com.idata.profile.mapper.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.graph.Organization;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper
public interface OrganizationMapper extends BaseMapper<Organization> {

    @Insert("""
            INSERT INTO organizations (
                id, canonical_name, importance_score, is_high_value, content_count, dedup_status
            )
            VALUES (gen_random_uuid(), #{canonicalName}, #{importanceScore}, FALSE, 1, 'pending')
            """)
    int insertEntity(@Param("canonicalName") String canonicalName,
                     @Param("importanceScore") BigDecimal importanceScore);

    @Select("SELECT COUNT(*) FROM organizations WHERE dedup_status = #{dedupStatus}")
    long countByDedupStatus(@Param("dedupStatus") String dedupStatus);

    @Select("SELECT * FROM organizations WHERE canonical_name = #{canonicalName} " +
            "AND dedup_status = 'pending' ORDER BY created_at ASC")
    List<Organization> selectPendingByCanonicalName(@Param("canonicalName") String canonicalName);

    @Select("SELECT canonical_name FROM organizations WHERE dedup_status = 'pending' " +
            "GROUP BY canonical_name HAVING COUNT(*) > 1 LIMIT #{limit}")
    List<String> selectDuplicateCanonicalNames(@Param("limit") int limit);

    @Update("""
            UPDATE organizations SET
                content_count = #{contentCount},
                merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                dedup_status = 'canonical',
                updated_at = NOW()
            WHERE id = #{survivorId}
            """)
    int updateSurvivorAfterMerge(@Param("survivorId") UUID survivorId,
                                 @Param("contentCount") int contentCount,
                                 @Param("mergedIds") UUID[] mergedIds);

    @Select("SELECT dedup_status AS status, COUNT(*) AS cnt FROM organizations GROUP BY dedup_status")
    List<Map<String, Object>> selectDedupStatusStats();

    @Select("SELECT EXISTS(SELECT 1 FROM organizations WHERE id = #{id})")
    boolean existsById(@Param("id") UUID id);

    @Select("SELECT id FROM organizations WHERE canonical_name = #{canonicalName} LIMIT 1")
    UUID selectIdByCanonicalName(@Param("canonicalName") String canonicalName);

    @Update("""
            UPDATE organizations
            SET merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int appendMergeHistory(@Param("id") UUID id, @Param("mergedIds") UUID[] mergedIds);
}
