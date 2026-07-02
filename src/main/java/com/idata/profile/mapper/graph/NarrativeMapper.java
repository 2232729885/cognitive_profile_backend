package com.idata.profile.mapper.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.graph.Narrative;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.UUID;

@Mapper
public interface NarrativeMapper extends BaseMapper<Narrative> {

    @Insert("""
            INSERT INTO narratives (
                id, canonical_label, lifecycle_state, content_count,
                account_count, importance_score, is_active, first_detected_at, claim_atoms
            )
            VALUES (
                gen_random_uuid(), #{canonicalLabel}, 'emerging', 1,
                0, #{importanceScore}, TRUE, NOW(),
                COALESCE(NULLIF(#{claimAtoms}, '')::jsonb, '[]'::jsonb)
            )
            ON CONFLICT (canonical_label)
            DO UPDATE SET
                content_count = narratives.content_count + 1,
                importance_score = GREATEST(narratives.importance_score, #{importanceScore}),
                claim_atoms = CASE
                    WHEN #{claimAtoms} IS NULL OR #{claimAtoms} = '' THEN narratives.claim_atoms
                    ELSE CAST(#{claimAtoms} AS jsonb)
                END,
                updated_at = NOW()
            """)
    int upsertByCanonicalLabel(@Param("canonicalLabel") String canonicalLabel,
                               @Param("importanceScore") BigDecimal importanceScore,
                               @Param("claimAtoms") String claimAtoms);

    @Select("SELECT EXISTS(SELECT 1 FROM narratives WHERE id = #{id})")
    boolean existsById(@Param("id") UUID id);

    @Select("SELECT id FROM narratives WHERE canonical_label = #{canonicalLabel} LIMIT 1")
    UUID selectIdByCanonicalLabel(@Param("canonicalLabel") String canonicalLabel);

    @Update("""
            UPDATE narratives
            SET merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int appendMergeHistory(@Param("id") UUID id, @Param("mergedIds") UUID[] mergedIds);
}
