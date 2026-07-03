package com.idata.profile.mapper.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.graph.Narrative;
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
public interface NarrativeMapper extends BaseMapper<Narrative> {

    @Insert("""
            INSERT INTO narratives (
                id, canonical_label, lifecycle_state, content_count,
                account_count, importance_score, is_active, dedup_status, first_detected_at, claim_atoms
            )
            VALUES (
                gen_random_uuid(), #{canonicalLabel}, 'emerging', 1,
                0, #{importanceScore}, TRUE, 'pending', NOW(),
                COALESCE(NULLIF(#{claimAtoms}, '')::jsonb, '[]'::jsonb)
            )
            """)
    int insertEntity(@Param("canonicalLabel") String canonicalLabel,
                     @Param("importanceScore") BigDecimal importanceScore,
                     @Param("claimAtoms") String claimAtoms);

    @Select("SELECT COUNT(*) FROM narratives WHERE dedup_status = #{dedupStatus}")
    long countByDedupStatus(@Param("dedupStatus") String dedupStatus);

    @Select("SELECT * FROM narratives WHERE dedup_status = #{status} " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<Narrative> selectByDedupStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT * FROM narratives WHERE canonical_label = #{canonicalLabel} " +
            "AND dedup_status = 'pending' ORDER BY created_at ASC")
    List<Narrative> selectPendingByCanonicalName(@Param("canonicalLabel") String canonicalLabel);

    @Select("SELECT canonical_label FROM narratives WHERE dedup_status = 'pending' " +
            "GROUP BY canonical_label HAVING COUNT(*) > 1 LIMIT #{limit}")
    List<String> selectDuplicateCanonicalNames(@Param("limit") int limit);

    @Update("""
            UPDATE narratives SET
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

    @Select("SELECT dedup_status AS status, COUNT(*) AS cnt FROM narratives GROUP BY dedup_status")
    List<Map<String, Object>> selectDedupStatusStats();

    @Select("SELECT EXISTS(SELECT 1 FROM narratives WHERE id = #{id})")
    boolean existsById(@Param("id") UUID id);

    @Select("SELECT id FROM narratives WHERE canonical_label = #{canonicalLabel} LIMIT 1")
    UUID selectIdByCanonicalLabel(@Param("canonicalLabel") String canonicalLabel);

    @Select("SELECT id FROM narratives WHERE canonical_label = #{label} " +
            "ORDER BY CASE dedup_status WHEN 'canonical' THEN 0 WHEN 'pending' THEN 1 ELSE 2 END, " +
            "created_at ASC LIMIT 1")
    UUID selectCanonicalIdByLabel(@Param("label") String label);

    @Update("""
            UPDATE narratives
            SET merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int appendMergeHistory(@Param("id") UUID id, @Param("mergedIds") UUID[] mergedIds);
}
