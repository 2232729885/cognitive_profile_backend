package com.idata.profile.mapper.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.graph.Person;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Mapper
public interface PersonMapper extends BaseMapper<Person> {

    /**
     * 画像生成定时任务选取候选人：见 batch.profile.PersonProfileGenerationJob。
     * 排除7天内已生成active画像的人物，按importanceScore排序。
     */
    @Select("SELECT p.* FROM persons p WHERE p.id NOT IN ( " +
            "  SELECT person_id FROM person_profiles " +
            "  WHERE status = 'active' AND generated_at > NOW() - INTERVAL '7 days' " +
            ") ORDER BY p.importance_score DESC LIMIT #{limit}")
    List<Person> selectCandidatesForProfileGeneration(@Param("limit") int limit);

    @Insert("""
            WITH updated AS (
                UPDATE persons
                SET content_count = content_count + 1,
                    last_seen_at = NOW(),
                    importance_score = GREATEST(importance_score, #{importanceScore}),
                    updated_at = NOW()
                WHERE canonical_name = #{canonicalName}
                RETURNING id
            )
            INSERT INTO persons (
                id, canonical_name, importance_score, is_high_value,
                content_count, first_seen_at, last_seen_at
            )
            SELECT gen_random_uuid(), #{canonicalName}, #{importanceScore}, FALSE, 1, NOW(), NOW()
            WHERE NOT EXISTS (SELECT 1 FROM updated)
            """)
    int upsertByCanonicalName(@Param("canonicalName") String canonicalName,
                              @Param("importanceScore") BigDecimal importanceScore);

    @Select("SELECT id FROM persons WHERE canonical_name = #{canonicalName} LIMIT 1")
    UUID selectIdByCanonicalName(@Param("canonicalName") String canonicalName);

    @Select("SELECT EXISTS(SELECT 1 FROM persons WHERE id = #{id})")
    boolean existsById(@Param("id") UUID id);

    @Update("""
            UPDATE persons
            SET merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int appendMergeHistory(@Param("id") UUID id, @Param("mergedIds") UUID[] mergedIds);
}
