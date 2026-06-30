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
            WITH updated AS (
                UPDATE narratives
                SET content_count = content_count + 1,
                    importance_score = GREATEST(importance_score, #{importanceScore}),
                    claim_atoms = CASE
                        WHEN #{claimAtoms} IS NULL OR #{claimAtoms} = '' THEN claim_atoms
                        ELSE CAST(#{claimAtoms} AS jsonb)
                    END,
                    updated_at = NOW()
                WHERE canonical_label = #{canonicalLabel}
                RETURNING id
            )
            INSERT INTO narratives (
                id, canonical_label, lifecycle_state, content_count,
                account_count, importance_score, is_active, first_detected_at, claim_atoms
            )
            SELECT gen_random_uuid(), #{canonicalLabel}, 'emerging', 1,
                   0, #{importanceScore}, TRUE, NOW(),
                   COALESCE(NULLIF(#{claimAtoms}, '')::jsonb, '[]'::jsonb)
            WHERE NOT EXISTS (SELECT 1 FROM updated)
            """)
    int upsertByCanonicalLabel(@Param("canonicalLabel") String canonicalLabel,
                               @Param("importanceScore") BigDecimal importanceScore,
                               @Param("claimAtoms") String claimAtoms);

    @Select("SELECT EXISTS(SELECT 1 FROM narratives WHERE id = #{id})")
    boolean existsById(@Param("id") UUID id);

    @Update("""
            UPDATE narratives
            SET merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int appendMergeHistory(@Param("id") UUID id, @Param("mergedIds") UUID[] mergedIds);
}
