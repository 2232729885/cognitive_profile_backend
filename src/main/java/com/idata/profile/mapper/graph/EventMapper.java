package com.idata.profile.mapper.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.graph.Event;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.UUID;

@Mapper
public interface EventMapper extends BaseMapper<Event> {

    @Insert("""
            WITH lock AS (
                SELECT pg_advisory_xact_lock(hashtext('event:' || #{canonicalName}))
            ),
            updated AS (
                UPDATE events
                SET content_count = content_count + 1,
                    importance_score = GREATEST(importance_score, #{importanceScore}),
                    updated_at = NOW()
                WHERE canonical_name = #{canonicalName}
                  AND EXISTS (SELECT 1 FROM lock)
                RETURNING id
            )
            INSERT INTO events (
                id, canonical_name, importance_score, content_count
            )
            SELECT gen_random_uuid(), #{canonicalName}, #{importanceScore}, 1
            FROM lock
            WHERE NOT EXISTS (SELECT 1 FROM updated)
            """)
    int upsertByCanonicalName(@Param("canonicalName") String canonicalName,
                              @Param("importanceScore") BigDecimal importanceScore);

    @Select("SELECT EXISTS(SELECT 1 FROM events WHERE id = #{id})")
    boolean existsById(@Param("id") UUID id);

    @Select("SELECT id FROM events WHERE canonical_name = #{canonicalName} LIMIT 1")
    UUID selectIdByCanonicalName(@Param("canonicalName") String canonicalName);

    @Update("""
            UPDATE events
            SET merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int appendMergeHistory(@Param("id") UUID id, @Param("mergedIds") UUID[] mergedIds);
}
