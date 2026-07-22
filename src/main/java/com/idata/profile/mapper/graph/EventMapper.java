package com.idata.profile.mapper.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.graph.Event;
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
public interface EventMapper extends BaseMapper<Event> {

    @Insert("""
            INSERT INTO events (
                id, canonical_name, event_type, importance_score, content_count, dedup_status
            )
            VALUES (#{id}, #{canonicalName}, #{eventType}, #{importanceScore}, 1, 'pending')
            ON CONFLICT (id) DO UPDATE SET
                event_type = COALESCE(EXCLUDED.event_type, events.event_type),
                importance_score = GREATEST(events.importance_score, EXCLUDED.importance_score),
                content_count = events.content_count + 1,
                updated_at = NOW()
            """)
    int insertEntity(@Param("id") UUID id,
                     @Param("canonicalName") String canonicalName,
                     @Param("eventType") String eventType,
                     @Param("importanceScore") BigDecimal importanceScore);

    @Update("""
            UPDATE events
            SET event_type = COALESCE(#{eventType}, event_type),
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateExtractedAttributes(@Param("id") UUID id,
                                  @Param("eventType") String eventType);

    @Select("SELECT COUNT(*) FROM events WHERE dedup_status = #{dedupStatus}")
    long countByDedupStatus(@Param("dedupStatus") String dedupStatus);

    @Select("SELECT * FROM events WHERE dedup_status = #{status} " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<Event> selectByDedupStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT * FROM events WHERE dedup_status = 'canonical' " +
            "ORDER BY event_heat_computed_at ASC NULLS FIRST LIMIT #{limit}")
    List<Event> selectReadyForHeatComputation(@Param("limit") int limit);

    @Select("SELECT * FROM events WHERE canonical_name = #{canonicalName} " +
            "AND dedup_status = 'pending' ORDER BY created_at ASC")
    List<Event> selectPendingByCanonicalName(@Param("canonicalName") String canonicalName);

    @Select("SELECT canonical_name FROM events WHERE dedup_status = 'pending' " +
            "GROUP BY canonical_name HAVING COUNT(*) > 1 LIMIT #{limit}")
    List<String> selectDuplicateCanonicalNames(@Param("limit") int limit);

    @Update("""
            UPDATE events SET
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

    @Select("SELECT dedup_status AS status, COUNT(*) AS cnt FROM events GROUP BY dedup_status")
    List<Map<String, Object>> selectDedupStatusStats();

    @Select("SELECT EXISTS(SELECT 1 FROM events WHERE id = #{id})")
    boolean existsById(@Param("id") UUID id);

    @Select("SELECT id FROM events WHERE canonical_name = #{canonicalName} LIMIT 1")
    UUID selectIdByCanonicalName(@Param("canonicalName") String canonicalName);

    @Select("SELECT id FROM events WHERE canonical_name = #{name} " +
            "ORDER BY CASE dedup_status WHEN 'canonical' THEN 0 WHEN 'pending' THEN 1 ELSE 2 END, " +
            "created_at ASC LIMIT 1")
    UUID selectCanonicalIdByName(@Param("name") String name);

    @Update("""
            UPDATE events
            SET merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int appendMergeHistory(@Param("id") UUID id, @Param("mergedIds") UUID[] mergedIds);

    @Update("""
            UPDATE events SET
                event_heat_level = #{heatLevel},
                event_heat_score = #{heatScore},
                event_heat_confidence = #{confidence},
                event_related_content_count = #{relatedContentCount},
                event_heat_computed_at = NOW()
            WHERE id = #{id}
            """)
    int updateEventHeat(@Param("id") UUID id,
                        @Param("heatLevel") String heatLevel,
                        @Param("heatScore") BigDecimal heatScore,
                        @Param("confidence") BigDecimal confidence,
                        @Param("relatedContentCount") int relatedContentCount);
}
