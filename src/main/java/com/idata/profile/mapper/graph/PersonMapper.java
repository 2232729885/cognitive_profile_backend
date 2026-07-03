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
import java.util.Map;
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
            INSERT INTO persons (
                id, canonical_name, importance_score, is_high_value,
                content_count, dedup_status, first_seen_at, last_seen_at
            )
            VALUES (
                gen_random_uuid(), #{canonicalName}, #{importanceScore},
                FALSE, 1, 'pending', NOW(), NOW()
            )
            """)
    int insertEntity(@Param("canonicalName") String canonicalName,
                     @Param("importanceScore") BigDecimal importanceScore);

    @Select("SELECT COUNT(*) FROM persons WHERE dedup_status = #{dedupStatus}")
    long countByDedupStatus(@Param("dedupStatus") String dedupStatus);

    @Select("SELECT * FROM persons WHERE canonical_name = #{canonicalName} " +
            "AND dedup_status = 'pending' ORDER BY created_at ASC")
    List<Person> selectPendingByCanonicalName(@Param("canonicalName") String canonicalName);

    @Select("SELECT canonical_name FROM persons WHERE dedup_status = 'pending' " +
            "GROUP BY canonical_name HAVING COUNT(*) > 1 LIMIT #{limit}")
    List<String> selectDuplicateCanonicalNames(@Param("limit") int limit);

    @Update("""
            UPDATE persons SET
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

    @Select("SELECT dedup_status AS status, COUNT(*) AS cnt FROM persons GROUP BY dedup_status")
    List<Map<String, Object>> selectDedupStatusStats();

    @Select("SELECT id FROM persons WHERE canonical_name = #{canonicalName} LIMIT 1")
    UUID selectIdByCanonicalName(@Param("canonicalName") String canonicalName);

    @Select("SELECT id FROM persons WHERE canonical_name = #{name} " +
            "ORDER BY CASE dedup_status WHEN 'canonical' THEN 0 WHEN 'pending' THEN 1 ELSE 2 END, " +
            "created_at ASC LIMIT 1")
    UUID selectCanonicalIdByName(@Param("name") String name);

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
